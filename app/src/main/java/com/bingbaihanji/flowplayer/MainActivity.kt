package com.bingbaihanji.flowplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bingbaihanji.flowplayer.core.ui.theme.FlowPlayerTheme
import com.bingbaihanji.flowplayer.feature.settings.SettingsViewModel
import com.bingbaihanji.flowplayer.navigation.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 主题设置来自 DataStore；切换后由 Compose 重组即时生效，不重启 Activity。
            val settingsViewModel: SettingsViewModel = viewModel()
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
            FlowPlayerTheme(themeMode = settings.themeMode) {
                AppRoot()
            }
        }
    }
}
