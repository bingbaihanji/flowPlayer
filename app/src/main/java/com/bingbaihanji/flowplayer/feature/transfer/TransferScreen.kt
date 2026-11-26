package com.bingbaihanji.flowplayer.feature.transfer

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.bingbaihanji.flowplayer.core.ui.components.SecondaryScreenScaffold

@Composable
fun TransferScreen(onBack: () -> Unit) {
    SecondaryScreenScaffold(title = "接收文件", onBack = onBack) {
        Text("WiFi 传输将在 M4 实现。")
    }
}
