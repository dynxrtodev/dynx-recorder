package com.example.screenrecorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.screenrecorder.R
import com.example.screenrecorder.model.AudioSourceMode
import com.example.screenrecorder.model.VideoQuality
import com.example.screenrecorder.recorder.RecorderState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecorderScreen(
    viewModel: MainViewModel,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    val config by viewModel.config.collectAsState()
    val state by viewModel.recorderState.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(state)

            ControlRow(
                state = state,
                onStartClick = onStartClick,
                onStopClick = onStopClick,
                onPauseClick = onPauseClick,
                onResumeClick = onResumeClick,
            )

            SettingsSection(title = stringResource(R.string.section_video)) {
                Text(
                    stringResource(R.string.label_resolution),
                    style = MaterialTheme.typography.labelLarge,
                )
                // FlowRow agar chip membungkus ke baris berikutnya di layar sempit.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoQuality.entries.forEach { quality ->
                        FilterChip(
                            selected = config.quality == quality,
                            onClick = { viewModel.setQuality(quality) },
                            label = { Text(quality.label) },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.label_fps, config.fps),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(24, 30, 48, 60).forEach { fps ->
                        FilterChip(
                            selected = config.fps == fps,
                            onClick = { viewModel.setFps(fps) },
                            label = { Text("$fps") },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                val autoBitrate = config.videoBitrate == 0
                Text(
                    if (autoBitrate) {
                        stringResource(R.string.label_bitrate_auto)
                    } else {
                        stringResource(R.string.label_bitrate, config.videoBitrate / 1_000_000)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    // 0 diperlakukan sebagai "otomatis".
                    value = (config.videoBitrate / 1_000_000).toFloat(),
                    onValueChange = { viewModel.setVideoBitrate(it.toInt() * 1_000_000) },
                    valueRange = 0f..60f,
                    steps = 59,
                )
            }

            SettingsSection(title = stringResource(R.string.section_audio)) {
                Column(Modifier.selectableGroup()) {
                    AudioSourceMode.entries.forEach { mode ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = config.audioMode == mode,
                                onClick = { viewModel.setAudioMode(mode) },
                            )
                            Text(audioModeLabel(mode))
                        }
                    }
                }

                if (config.audioMode.needsMic) {
                    Text(
                        stringResource(R.string.label_mic_gain, config.micGain),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = config.micGain,
                        onValueChange = { viewModel.setMicGain(it) },
                        valueRange = 0f..3f,
                    )
                }
                if (config.audioMode.needsPlayback) {
                    Text(
                        stringResource(R.string.label_internal_gain, config.internalGain),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = config.internalGain,
                        onValueChange = { viewModel.setInternalGain(it) },
                        valueRange = 0f..3f,
                    )
                }
                Text(
                    stringResource(R.string.hint_internal_audio),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SettingsSection(title = stringResource(R.string.section_misc)) {
                SwitchRow(
                    label = stringResource(R.string.label_floating_button),
                    checked = config.useFloatingButton,
                    onCheckedChange = { viewModel.setFloatingButton(it) },
                )
                OutlinedButton(onClick = onOpenOverlaySettings) {
                    Text(stringResource(R.string.action_grant_overlay))
                }

                Text(
                    stringResource(R.string.label_countdown, config.countdownSeconds),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 3, 5, 10).forEach { sec ->
                        FilterChip(
                            selected = config.countdownSeconds == sec,
                            onClick = { viewModel.setCountdown(sec) },
                            label = { Text(if (sec == 0) stringResource(R.string.off) else "${sec}s") },
                        )
                    }
                }

                Text(
                    if (config.maxDurationSeconds == 0) {
                        stringResource(R.string.label_max_duration_off)
                    } else {
                        stringResource(R.string.label_max_duration, config.maxDurationSeconds / 60)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 5, 15, 30).forEach { minutes ->
                        FilterChip(
                            selected = config.maxDurationSeconds == minutes * 60,
                            onClick = { viewModel.setMaxDuration(minutes * 60) },
                            label = {
                                Text(
                                    if (minutes == 0) {
                                        stringResource(R.string.off)
                                    } else {
                                        "${minutes}m"
                                    }
                                )
                            },
                        )
                    }
                }

                OutlinedButton(
                    onClick = onOpenGallery,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.VideoLibrary, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_open_recordings))
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: RecorderState) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val (text, detail) = when (state) {
                RecorderState.Idle -> stringResource(R.string.status_idle) to null
                is RecorderState.Recording -> stringResource(R.string.status_recording) to null
                is RecorderState.Paused ->
                    stringResource(R.string.status_paused) to formatDuration(state.elapsedMs)

                is RecorderState.Finished ->
                    stringResource(R.string.status_saved) to formatDuration(state.durationMs)

                is RecorderState.Failed -> stringResource(R.string.status_failed) to state.message
            }
            Text(text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ControlRow(
    state: RecorderState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            is RecorderState.Recording -> {
                Button(onClick = onPauseClick, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Pause, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_pause))
                }
                Button(onClick = onStopClick, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_stop))
                }
            }

            is RecorderState.Paused -> {
                Button(onClick = onResumeClick, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_resume))
                }
                Button(onClick = onStopClick, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_stop))
                }
            }

            else -> {
                Button(onClick = onStartClick, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_start))
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun audioModeLabel(mode: AudioSourceMode): String = stringResource(
    when (mode) {
        AudioSourceMode.NONE -> R.string.audio_none
        AudioSourceMode.MIC -> R.string.audio_mic
        AudioSourceMode.INTERNAL -> R.string.audio_internal
        AudioSourceMode.MIC_AND_INTERNAL -> R.string.audio_both
    }
)

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
}
