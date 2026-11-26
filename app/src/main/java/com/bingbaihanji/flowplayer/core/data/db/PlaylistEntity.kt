package com.bingbaihanji.flowplayer.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 播放列表。条目通过 [PlaylistItemEntity] 关联本表，删除播放列表时条目级联删除。
 */
@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)
