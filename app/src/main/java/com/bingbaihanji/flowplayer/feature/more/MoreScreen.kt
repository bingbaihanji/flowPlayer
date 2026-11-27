package com.bingbaihanji.flowplayer.feature.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bingbaihanji.flowplayer.core.ui.components.ScreenScaffold

/**
 * “更多”页入口。第一版按设计只保留设置、接收文件与关于三项；
 * 安全相册、意见反馈与隐私安全不在第一版范围。
 */
@Composable
fun MoreScreen(
    onOpenSettings: () -> Unit,
    onOpenTransfer: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    ScreenScaffold(title = "更多") {
        Card(modifier = Modifier.fillMaxWidth()) {
            MoreEntryRow(
                icon = Icons.Filled.Settings,
                title = "设置",
                onClick = onOpenSettings,
            )
            HorizontalDivider()
            MoreEntryRow(
                icon = Icons.Filled.FolderOpen,
                title = "接收文件",
                onClick = onOpenTransfer,
            )
            HorizontalDivider()
            MoreEntryRow(
                icon = Icons.Filled.Info,
                title = "关于",
                onClick = onOpenAbout,
            )
        }
    }
}

@Composable
private fun MoreEntryRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
