package com.bingbaihanji.flowplayer.core.data.db

/**
 * 播放列表列表页使用的查询投影：播放列表信息 + 条目数量。
 */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val itemCount: Int,
)
