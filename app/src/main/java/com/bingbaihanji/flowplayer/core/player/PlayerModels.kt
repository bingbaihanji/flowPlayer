package com.bingbaihanji.flowplayer.core.player

/**
 * 播放模式。与设置页的四项保持一致。
 */
enum class PlayMode(val label: String) {
    SEQUENTIAL("顺序播放"),
    REPEAT_ONE("单曲循环"),
    SHUFFLE("随机播放"),

    /** 当前音视频播放结束后暂停，不自动切到下一项。 */
    PAUSE_AT_END("播放完暂停"),
}

/**
 * 轨道类型。TEXT 包含内嵌字幕与外挂字幕。
 */
enum class TrackKind {
    AUDIO,
    VIDEO,
    TEXT,
}

/**
 * 画面比例。16:9 / 4:3 使用外层固定比例容器，其余映射到 PlayerView 的 resizeMode。
 */
enum class AspectMode(val label: String) {
    FIT("适应"),
    FILL("填充"),
    ZOOM("裁剪"),
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3"),
}

/**
 * 播放队列中的一条媒体。
 *
 * [uri] 用于 Media3 读取，可能是 content:// 或 file://。
 * [path] 作为历史记录主键；对 MediaStore 条目应保持与 MediaFile.path 一致。
 */
data class PlayerQueueItem(
    val uri: String,
    val path: String,
    val title: String,
    val mimeType: String = "",
    val durationMs: Long = 0L,
)

/**
 * 一次播放请求。队列通过 Intent extras 传入 PlayerActivity。
 */
data class PlaybackRequest(
    val items: List<PlayerQueueItem>,
    val startIndex: Int = 0,
    val startPositionMs: Long = 0L,
    val queueTitle: String = "",
    val saveHistory: Boolean = true,
) {
    val safeStartIndex: Int
        get() = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
}

/**
 * UI 可选中的一条轨道。
 */
data class PlayerTrack(
    val id: String,
    val kind: TrackKind,
    val label: String,
    val language: String?,
    val selected: Boolean,
    val groupIndex: Int,
    val trackIndex: Int,
)

/**
 * 播放器对 UI 暴露的快照状态。
 */
data class PlayerPlaybackState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isEnded: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val speed: Float = 1.0f,
    val playMode: PlayMode = PlayMode.SEQUENTIAL,
    val currentIndex: Int = 0,
    val itemCount: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val title: String = "",
    val uri: String = "",
    val path: String = "",
    val mimeType: String = "",
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val tracks: List<PlayerTrack> = emptyList(),
    val subtitlesEnabled: Boolean = false,
    val errorMessage: String? = null,
) {
    val hasVideo: Boolean
        get() = videoWidth > 0 && videoHeight > 0
}
