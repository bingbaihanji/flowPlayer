package com.bingbaihanji.flowplayer.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bingbaihanji.flowplayer.core.data.settings.SpeedMode
import com.bingbaihanji.flowplayer.core.data.settings.ThemeMode
import com.bingbaihanji.flowplayer.core.player.PlayMode
import com.bingbaihanji.flowplayer.core.ui.components.SecondaryScreenScaffold

/**
 * 设置页：绿色顶栏 + “播放设置”分组。
 *
 * 三行分别展示播放模式、倍数模式、界面风格的当前值；点击弹单选对话框，
 * 选中即通过 [SettingsViewModel] 保存到 DataStore。
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel: SettingsViewModel = viewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var activeDialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }

    SecondaryScreenScaffold(title = "设置", onBack = onBack) {
        Text(
            text = "播放设置",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
        SettingRow(
            title = "播放模式",
            value = settings.playMode.label,
            onClick = { activeDialog = SettingsDialog.PLAY_MODE },
        )
        SettingRow(
            title = "倍数模式",
            value = settings.speedMode.label,
            onClick = { activeDialog = SettingsDialog.SPEED_MODE },
        )
        SettingRow(
            title = "界面风格",
            value = settings.themeMode.label,
            onClick = { activeDialog = SettingsDialog.THEME_MODE },
        )
    }

    when (activeDialog) {
        SettingsDialog.PLAY_MODE -> ChoiceDialog(
            title = "播放模式",
            options = PlayMode.entries,
            selected = settings.playMode,
            label = { it.label },
            onSelect = {
                viewModel.setPlayMode(it)
                activeDialog = null
            },
            onDismiss = { activeDialog = null },
        )

        SettingsDialog.SPEED_MODE -> ChoiceDialog(
            title = "倍数模式",
            options = SpeedMode.entries,
            selected = settings.speedMode,
            label = { it.label },
            onSelect = {
                viewModel.setSpeedMode(it)
                activeDialog = null
            },
            onDismiss = { activeDialog = null },
        )

        SettingsDialog.THEME_MODE -> ChoiceDialog(
            title = "界面风格",
            options = ThemeMode.entries,
            selected = settings.themeMode,
            label = { it.label },
            onSelect = {
                viewModel.setThemeMode(it)
                activeDialog = null
            },
            onDismiss = { activeDialog = null },
        )

        null -> Unit
    }
}

@Composable
private fun SettingRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(option) },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == selected,
                            onClick = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = label(option),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

private enum class SettingsDialog {
    PLAY_MODE,
    SPEED_MODE,
    THEME_MODE,
}
