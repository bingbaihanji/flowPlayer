package com.bingbaihanji.flowplayer.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingbaihanji.flowplayer.core.player.PlayerPlaybackState
import com.bingbaihanji.flowplayer.core.ui.theme.EvPrimary
import java.util.Locale

/** 播放时间格式：小于 1 小时显示 mm:ss，否则 h:mm:ss。 */
internal fun formatTime(positionMs: Long): String {
    val safeMs = positionMs.coerceAtLeast(0L)
    val totalSeconds = safeMs / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/** 播放页顶部栏：返回、标题、更多。 */
@Composable
fun PlayerTopBar(
    title: String,
    onBack: () -> Unit,
    moreExpanded: Boolean,
    onMoreClick: () -> Unit,
    onDismissMore: () -> Unit,
    onOpenSheet: (PlayerSheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xE6000000),
                    0.7f to Color(0x99000000),
                    1f to Color.Transparent,
                ),
            )
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White,
            )
        }
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box {
            IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "更多",
                    tint = Color.White,
                )
            }
            DropdownMenu(expanded = moreExpanded, onDismissRequest = onDismissMore) {
                MoreMenuItem("倍速") { onOpenSheet(PlayerSheet.SPEED) }
                MoreMenuItem("播放模式") { onOpenSheet(PlayerSheet.PLAY_MODE) }
                MoreMenuItem("字幕") { onOpenSheet(PlayerSheet.SUBTITLE) }
                MoreMenuItem("音轨") { onOpenSheet(PlayerSheet.AUDIO_TRACK) }
                MoreMenuItem("视频轨") { onOpenSheet(PlayerSheet.VIDEO_TRACK) }
                MoreMenuItem("画面比例") { onOpenSheet(PlayerSheet.ASPECT) }
                MoreMenuItem("定时停止") { onOpenSheet(PlayerSheet.SLEEP_TIMER) }
            }
        }
    }
}

@Composable
private fun MoreMenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text = text, color = Color.White) },
        onClick = onClick,
    )
}

/** 右侧竖排的锁定、PiP 按钮。 */
@Composable
fun PlayerSideControls(
    showPip: Boolean,
    onToggleLock: () -> Unit,
    onEnterPip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(end = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onToggleLock) {
            Icon(
                imageVector = Icons.Filled.LockOpen,
                contentDescription = "锁定",
                tint = Color.White,
            )
        }
        if (showPip) {
            IconButton(onClick = onEnterPip) {
                Icon(
                    imageVector = Icons.Filled.PictureInPictureAlt,
                    contentDescription = "画中画",
                    tint = Color.White,
                )
            }
        }
    }
}

/** 锁定态只保留一个解锁按钮，其余控件和手势全部禁用。 */
@Composable
fun PlayerLockOverlay(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Box(
            modifier = Modifier
                .padding(end = 16.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onUnlock),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "解锁",
                tint = Color.White,
            )
        }
    }
}

/** 底部：进度条、时间、上一集/播放暂停/下一集/倍速/队列/全屏。 */
@Composable
fun PlayerBottomBar(
    state: PlayerPlaybackState,
    seekPreviewMs: Long?,
    speedText: String,
    isLandscape: Boolean,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: (Long) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSpeedClick: () -> Unit,
    onQueueClick: () -> Unit,
    onFullscreenClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayPosition = seekPreviewMs ?: state.positionMs
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.3f to Color(0xB3000000),
                    1f to Color(0xF2000000),
                ),
            )
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
    ) {
        PlayerSeekBar(
            positionMs = displayPosition,
            durationMs = state.durationMs,
            bufferedMs = state.bufferedMs,
            enabled = state.durationMs > 0,
            onSeekPreview = onSeekPreview,
            onSeekCommit = onSeekCommit,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(displayPosition),
                color = Color.White,
                fontSize = 13.sp,
            )
            Text(
                text = formatTime(state.durationMs),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerControlIcon(Icons.Filled.SkipPrevious, "上一集", onPrevious)
            PlayerControlIcon(
                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.isPlaying) "暂停" else "播放",
                onClick = onPlayPause,
                iconSize = 34,
            )
            PlayerControlIcon(Icons.Filled.SkipNext, "下一集", onNext)
            Text(
                text = speedText,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSpeedClick)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            )
            PlayerControlIcon(Icons.Filled.QueueMusic, "播放队列", onQueueClick)
            PlayerControlIcon(
                imageVector = if (isLandscape) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = "全屏",
                onClick = onFullscreenClick,
            )
        }
    }
}

@Composable
private fun PlayerControlIcon(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    iconSize: Int = 30,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

/**
 * 细进度条。按下即预览目标位置，拖动实时更新，松手才真正 seek。
 * 缓冲进度用白色半透明显示在已播放进度下方。
 */
@Composable
fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    enabled: Boolean,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentDuration by rememberUpdatedState(durationMs)
    val currentPreview by rememberUpdatedState(onSeekPreview)
    val currentCommit by rememberUpdatedState(onSeekCommit)
    var dragTargetMs by remember { mutableStateOf<Long?>(null) }

    val totalMs = durationMs.coerceAtLeast(0L)
    val shownPosition = dragTargetMs ?: positionMs
    val thumbFraction = if (totalMs > 0) {
        (shownPosition.toFloat() / totalMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val bufferedFraction = if (totalMs > 0) {
        (bufferedMs.toFloat() / totalMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val widthPx = size.width.toFloat()
                    val duration = currentDuration
                    if (widthPx <= 0f || duration <= 0L) return@awaitEachGesture
                    var target = ((down.position.x / widthPx).coerceIn(0f, 1f) * duration).toLong()
                    dragTargetMs = target
                    currentPreview(target)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        target = ((change.position.x / widthPx).coerceIn(0f, 1f) * duration).toLong()
                        dragTargetMs = target
                        currentPreview(target)
                        change.consume()
                    }
                    currentCommit(target)
                    dragTargetMs = null
                }
            },
    ) {
        val trackHeight = 3.dp.toPx()
        val thumbRadius = 4.5.dp.toPx()
        val centerY = size.height / 2f
        val corner = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.18f),
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(size.width, trackHeight),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.4f),
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(size.width * bufferedFraction, trackHeight),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = EvPrimary,
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(size.width * thumbFraction, trackHeight),
            cornerRadius = corner,
        )
        drawCircle(
            color = Color.White,
            radius = thumbRadius,
            center = Offset(size.width * thumbFraction, centerY),
        )
    }
}
