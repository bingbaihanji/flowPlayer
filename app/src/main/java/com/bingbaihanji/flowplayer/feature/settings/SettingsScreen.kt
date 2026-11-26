package com.bingbaihanji.flowplayer.feature.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.bingbaihanji.flowplayer.core.ui.components.SecondaryScreenScaffold

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    SecondaryScreenScaffold(title = "设置", onBack = onBack) {
        Text("设置将在 M5 实现。")
    }
}
