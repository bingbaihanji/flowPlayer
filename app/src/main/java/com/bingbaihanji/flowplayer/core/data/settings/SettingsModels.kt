package com.bingbaihanji.flowplayer.core.data.settings

import com.bingbaihanji.flowplayer.core.data.file.FileSortBy
import com.bingbaihanji.flowplayer.core.player.PlayMode

/**
 * 界面主题模式。MainActivity 读取后交给 FlowPlayerTheme，不重启 Activity 即生效。
 */
enum class ThemeMode(val label: String) {
    FOLLOW_SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

/**
 * 播放器倍速菜单模式：常规倍数展示常用档位，细分倍数展示更密集的档位。
 */
enum class SpeedMode(val label: String) {
    NORMAL("常规倍数"),
    FINE("细分倍数"),
}

/**
 * 全部设置的只读快照。
 *
 * 默认值即“首次安装/键缺失/枚举字符串无法解析”时的兜底值：
 * 顺序播放、常规倍数、跟随系统、默认续播、不显示隐藏文件、
 * 按修改时间倒序、列表视图、传输 token 为空（首次启动时生成）。
 */
data class AppSettings(
    val playMode: PlayMode = PlayMode.SEQUENTIAL,
    val speedMode: SpeedMode = SpeedMode.NORMAL,
    val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    val continueLastPosition: Boolean = true,
    val showHiddenFiles: Boolean = false,
    val sortBy: FileSortBy = FileSortBy.TIME,
    val sortAscending: Boolean = false,
    val libraryGridView: Boolean = false,
    val transferToken: String = "",
)
