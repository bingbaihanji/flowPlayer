package com.bingbaihanji.flowplayer.feature.transfer

import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingbaihanji.flowplayer.core.data.file.FileFormatters
import com.bingbaihanji.flowplayer.core.transfer.TransferFile
import java.util.Locale

private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "ts", "m4v", "3gp", "wmv", "rmvb")
private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "aac", "m4a", "ogg", "wav", "wma", "opus", "ape")
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")

/** 优先使用服务返回的 MIME，缺失或过于笼统时按扩展名推断。 */
internal fun transferFileMime(file: TransferFile): String {
    val provided = file.mimeType.trim()
    if (provided.isNotEmpty() && provided != "application/octet-stream") return provided
    val extension = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val fromMap = runCatching {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }.getOrNull()
    if (!fromMap.isNullOrBlank()) return fromMap
    return when (extension) {
        in VIDEO_EXTENSIONS -> "video/*"
        in AUDIO_EXTENSIONS -> "audio/*"
        in IMAGE_EXTENSIONS -> "image/*"
        else -> "application/octet-stream"
    }
}

internal fun transferFileIsVideo(file: TransferFile): Boolean {
    if (transferFileMime(file).startsWith("video/")) return true
    return file.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in VIDEO_EXTENSIONS
}

internal fun transferFileIsAudio(file: TransferFile): Boolean {
    if (transferFileMime(file).startsWith("audio/")) return true
    return file.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in AUDIO_EXTENSIONS
}

internal fun transferFileIsImage(file: TransferFile): Boolean {
    if (transferFileMime(file).startsWith("image/")) return true
    return file.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTENSIONS
}

private fun transferFileIcon(file: TransferFile): ImageVector = when {
    transferFileIsVideo(file) -> Icons.Filled.VideoFile
    transferFileIsAudio(file) -> Icons.Filled.MusicNote
    transferFileIsImage(file) -> Icons.Filled.Image
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun transferFileMeta(file: TransferFile): String {
    val parts = mutableListOf<String>()
    if (file.size > 0L) parts += FileFormatters.formatSize(file.size)
    if (file.lastModified > 0L) parts += FileFormatters.formatDate(file.lastModified)
    if (parts.isEmpty()) parts += transferFileMime(file)
    return parts.joinToString(" · ")
}

/**
 * 接收文件列表项：图标 + 名称 + 大小/时间，点击打开，右侧按钮删除。
 */
@Composable
internal fun TransferFileRow(
    file: TransferFile,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = transferFileIcon(file),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(23.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = transferFileMeta(file),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除文件",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
