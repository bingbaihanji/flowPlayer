package com.bingbaihanji.flowplayer.feature.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bingbaihanji.flowplayer.core.data.db.PlaylistItemEntity
import com.bingbaihanji.flowplayer.core.player.PlaybackRequest
import com.bingbaihanji.flowplayer.core.player.PlayerContract
import com.bingbaihanji.flowplayer.core.player.PlayerLauncher
import com.bingbaihanji.flowplayer.core.player.PlayerQueueItem
import com.bingbaihanji.flowplayer.core.ui.components.SecondaryScreenScaffold
import java.util.*

@Composable
internal fun PlaylistDetailScreen(
    title: String,
    items: List<PlaylistItemEntity>,
    onBack: () -> Unit,
    onRemoveItem: (Long) -> Unit,
) {
    val context = LocalContext.current
    val queue = items.map { item ->
        PlayerQueueItem(
            uri = PlayerContract.uriFor(item.path),
            path = item.path,
            title = playlistItemTitle(item),
            mimeType = item.mimeType,
            durationMs = item.durationMs,
        )
    }
    val playFrom: (Int) -> Unit = { index ->
        if (queue.isNotEmpty()) {
            PlayerLauncher.start(
                context,
                PlaybackRequest(
                    items = queue,
                    startIndex = index,
                    startPositionMs = 0L,
                    queueTitle = title,
                ),
            )
        }
    }

    SecondaryScreenScaffold(
        title = title,
        onBack = onBack,
    ) {
        if (items.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "播放列表暂无内容",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "后续可从此处查看加入列表的视频和音乐",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "共 ${items.size} 个条目",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { playFrom(0) }) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("播放全部")
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    PlaylistItemRow(
                        item = item,
                        onPlay = { playFrom(index) },
                        onRemove = { onRemoveItem(item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistItemRow(
    item: PlaylistItemEntity,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    val isAudio = item.mimeType.startsWith("audio")
    val displayTitle = playlistItemTitle(item)
    val durationText = if (item.durationMs > 0) formatDuration(item.durationMs) else null
    val sizeText = formatFileSize(item.size).ifEmpty { null }
    val metaText = listOfNotNull(durationText, sizeText).joinToString(" · ")

    Surface(
        onClick = onPlay,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isAudio) Icons.Filled.MusicNote else Icons.Filled.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metaText.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = metaText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = "从播放列表移除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun playlistItemTitle(item: PlaylistItemEntity): String =
    item.title.ifBlank { item.path.substringAfterLast('/').ifBlank { "未知文件" } }

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.getDefault(), hours, minutes, seconds)
    } else {
        "%02d:%02d".format(Locale.getDefault(), minutes, seconds)
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) {
        "$bytes B"
    } else {
        "%.1f %s".format(Locale.getDefault(), value, units[unitIndex])
    }
}
