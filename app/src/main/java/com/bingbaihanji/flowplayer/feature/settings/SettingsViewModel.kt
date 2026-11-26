package com.bingbaihanji.flowplayer.feature.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bingbaihanji.flowplayer.core.data.settings.AppSettings
import com.bingbaihanji.flowplayer.core.data.settings.SettingsRepository
import com.bingbaihanji.flowplayer.core.data.settings.SpeedMode
import com.bingbaihanji.flowplayer.core.data.settings.ThemeMode
import com.bingbaihanji.flowplayer.core.player.PlayMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel：只通过 [SettingsRepository] 读写 DataStore 设置。
 *
 * 不使用 Hilt；作为 AndroidViewModel(Application) 由默认工厂创建。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository.from(application)

    /** DataStore 首个值到达前使用 [AppSettings] 默认值。 */
    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = AppSettings(),
        )

    fun setPlayMode(mode: PlayMode) {
        viewModelScope.launch {
            runCatching { repository.setPlayMode(mode) }
                .onFailure { Log.w(TAG, "保存播放模式失败", it) }
        }
    }

    fun setSpeedMode(mode: SpeedMode) {
        viewModelScope.launch {
            runCatching { repository.setSpeedMode(mode) }
                .onFailure { Log.w(TAG, "保存倍数模式失败", it) }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            runCatching { repository.setThemeMode(mode) }
                .onFailure { Log.w(TAG, "保存界面风格失败", it) }
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
