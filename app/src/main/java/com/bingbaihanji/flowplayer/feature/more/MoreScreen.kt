package com.bingbaihanji.flowplayer.feature.more

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bingbaihanji.flowplayer.core.ui.components.ScreenScaffold

@Composable
fun MoreScreen(
    onOpenTransfer: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ScreenScaffold(title = "更多") {
        Button(
            onClick = onOpenTransfer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("接收文件")
        }
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("设置")
        }
    }
}
