package com.bingbaihanji.flowplayer.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 播放历史。以 path 唯一索引去重，M3 播放器负责写入播放进度与最后播放时间。
 */
@Entity(
    tableName = "playback_history",
    indices = [Index(value = ["path"], unique = true)],
)
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val path: String,
    val title: String,
    val mimeType: String,
    val durationMs: Long,
    val positionMs: Long,
    val lastPlayedAt: Long,
)
