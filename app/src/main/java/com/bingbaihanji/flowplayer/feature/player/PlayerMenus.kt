package com.bingbaihanji.flowplayer.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bingbaihanji.flowplayer.core.player.AspectMode
import com.bingbaihanji.flowplayer.core.player.PlayMode
import com.bingbaihanji.flowplayer.core.player.PlayerPlaybackState
import com.bingbaihanji.flowplayer.core.player.PlayerQueueItem
import com.bingbaihanji.flowplayer.core.player.PlayerTrack
import com.bingbaihanji.flowplayer.core.player.TrackKind
import com.bingbaihanji.flowplayer.core.ui.theme.EvPrimary
import java.util.Locale
import kotlin.math.abs

/** 播放页可打开的底部菜单类型。 */
enum class PlayerSheet {
    SPEED,
    PLAY_MODE,
    SUBTITLE,
    AUDIO_TRACK,
    VIDEO_TRACK,
    ASPECT,
    SLEEP_TIMER,
    QUEUE,
}

/** 倍速列表：常规档位 / 0.1 步进细分档位。 */
enum class SpeedListMode { NORMAL, FINE }

private val NORMAL_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f)
private val FINE_SPEEDS = (2..24).map { it / 10f }

internal fun speedLabel(value: Float): String = "${speedText(value)}X"

private fun speedText(value: Float): String {
    val text = String.format(Locale.US, "%.2f", value)
    return if (text.endsWith("0")) text.dropLast(1) else text
}

private fun speedsFor(mode: SpeedListMode): List<Float> =
    when (mode) {
        SpeedListMode.NORMAL -> NORMAL_SPEEDS
        SpeedListMode.FINE -> FINE_SPEEDS
    }

/**
 * 播放页统一底部菜单。所有菜单共用同一个 ModalBottomSheet，内容由 [sheet] 决定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerMenuSheet(
    sheet: PlayerSheet,
    state: PlayerPlaybackState,
    timerRemainingMs: Long?,
    queueTitle: String,
    queue: List<PlayerQueueItem>,
    aspectMode: AspectMode,
    speedListMode: SpeedListMode,
    onSpeedListModeChange: (SpeedListMode) -> Unit,
    onDismiss: () -> Unit,
    onSpeed: (Float) -> Unit,
    onPlayMode: (PlayMode) -> Unit,
    onDisableSubtitles: () -> Unit,
    onSelectTrack: (PlayerTrack) -> Unit,
    onImportSubtitle: () -> Unit,
    onAspectMode: (AspectMode) -> Unit,
    onStartSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onSelectQueueIndex: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF141414),
        contentColor = Color.White,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sheetTitle(sheet, queue.size),
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                when (sheet) {
                    PlayerSheet.SPEED -> {
                        item {
                            SpeedModeSelector(
                                mode = speedListMode,
                                onModeChange = onSpeedListModeChange,
                            )
                        }
                        items(speedsFor(speedListMode)) { value ->
                            MenuRow(
                                text = speedLabel(value),
                                selected = abs(state.speed - value) < 0.01f,
                                onClick = {
                                    onSpeed(value)
                                    onDismiss()
                                },
                            )
                        }
                    }

                    PlayerSheet.PLAY_MODE -> {
                        items(PlayMode.entries) { mode ->
                            MenuRow(
                                text = mode.label,
                                selected = state.playMode == mode,
                                onClick = {
                                    onPlayMode(mode)
                                    onDismiss()
                                },
                            )
                        }
                    }

                    PlayerSheet.SUBTITLE -> {
                        val textTracks = state.tracks.filter { it.kind == TrackKind.TEXT }
                        item {
                            MenuRow(
                                text = "关闭字幕",
                                selected = !state.subtitlesEnabled,
                                onClick = {
                                    onDisableSubtitles()
                                    onDismiss()
                                },
                            )
                        }
                        if (textTracks.isEmpty()) {
                            item { EmptyHint("暂无内嵌字幕") }
                        } else {
                            items(textTracks) { track ->
                                MenuRow(
                                    text = trackLabel(track),
                                    selected = track.selected,
                                    onClick = {
                                        onSelectTrack(track)
                                        onDismiss()
                                    },
                                )
                            }
                        }
                        item {
                            MenuRow(
                                text = "导入外挂字幕（SRT/ASS/VTT）",
                                onClick = {
                                    onImportSubtitle()
                                    onDismiss()
                                },
                            )
                        }
                    }

                    PlayerSheet.AUDIO_TRACK -> {
                        val audioTracks = state.tracks.filter { it.kind == TrackKind.AUDIO }
                        if (audioTracks.isEmpty()) {
                            item { EmptyHint("暂无音轨") }
                        } else {
                            items(audioTracks) { track ->
                                MenuRow(
                                    text = trackLabel(track),
                                    selected = track.selected,
                                    onClick = {
                                        onSelectTrack(track)
                                        onDismiss()
                                    },
                                )
                            }
                        }
                    }

                    PlayerSheet.VIDEO_TRACK -> {
                        val videoTracks = state.tracks.filter { it.kind == TrackKind.VIDEO }
                        if (videoTracks.isEmpty()) {
                            item { EmptyHint("暂无可切换的视频轨") }
                        } else {
                            items(videoTracks) { track ->
                                MenuRow(
                                    text = trackLabel(track),
                                    selected = track.selected,
                                    onClick = {
                                        onSelectTrack(track)
                                        onDismiss()
                                    },
                                )
                            }
                        }
                    }

                    PlayerSheet.ASPECT -> {
                        items(AspectMode.entries) { mode ->
                            MenuRow(
                                text = mode.label,
                                selected = aspectMode == mode,
                                onClick = {
                                    onAspectMode(mode)
                                    onDismiss()
                                },
                            )
                        }
                    }

                    PlayerSheet.SLEEP_TIMER -> {
                        if (timerRemainingMs != null) {
                            item {
                                MenuRow(
                                    text = "剩余 ${formatTime(timerRemainingMs)}",
                                    selected = true,
                                    onClick = {},
                                )
                            }
                            item {
                                MenuRow(
                                    text = "取消定时停止",
                                    onClick = {
                                        onCancelSleepTimer()
                                        onDismiss()
                                    },
                                )
                            }
                        }
                        items(listOf(10, 30, 60, 90, 120)) { minutes ->
                            MenuRow(
                                text = "${minutes} 分钟",
                                onClick = {
                                    onStartSleepTimer(minutes)
                                    onDismiss()
                                },
                            )
                        }
                    }

                    PlayerSheet.QUEUE -> {
                        if (queueTitle.isNotBlank()) {
                            item { EmptyHint(queueTitle) }
                        }
                        if (queue.isEmpty()) {
                            item { EmptyHint("播放队列为空") }
                        } else {
                            itemsIndexed(queue) { index, item ->
                                QueueRow(
                                    index = index,
                                    item = item,
                                    selected = index == state.currentIndex,
                                    onClick = {
                                        onSelectQueueIndex(index)
                                        onDismiss()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sheetTitle(sheet: PlayerSheet, queueSize: Int): String = when (sheet) {
    PlayerSheet.SPEED -> "播放速度"
    PlayerSheet.PLAY_MODE -> "播放模式"
    PlayerSheet.SUBTITLE -> "字幕"
    PlayerSheet.AUDIO_TRACK -> "音轨"
    PlayerSheet.VIDEO_TRACK -> "视频轨"
    PlayerSheet.ASPECT -> "画面比例"
    PlayerSheet.SLEEP_TIMER -> "定时停止"
    PlayerSheet.QUEUE -> "播放队列（$queueSize）"
}

@Composable
private fun SpeedModeSelector(mode: SpeedListMode, onModeChange: (SpeedListMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SpeedModeTab("常规", mode == SpeedListMode.NORMAL) { onModeChange(SpeedListMode.NORMAL) }
        SpeedModeTab("细分", mode == SpeedListMode.FINE) { onModeChange(SpeedListMode.FINE) }
    }
}

@Composable
private fun SpeedModeTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) EvPrimary else Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0xFF233A2A) else Color(0xFF242424))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun MenuRow(
    text: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            color = if (selected) EvPrimary else Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = EvPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.5f),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
}

@Composable
private fun QueueRow(
    index: Int,
    item: PlayerQueueItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${index + 1}",
            color = if (selected) EvPrimary else Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(32.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title.ifBlank { item.path.substringAfterLast('/') },
                color = if (selected) EvPrimary else Color.White,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.durationMs > 0) {
                Text(
                    text = formatTime(item.durationMs),
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = EvPrimary,
            )
        }
    }
}

private fun trackLabel(track: PlayerTrack): String {
    val name = track.label.ifBlank { "轨道 ${track.trackIndex + 1}" }
    val language = track.language
    return if (!language.isNullOrBlank() && !name.contains(language)) {
        "$name（$language）"
    } else {
        name
    }
}
