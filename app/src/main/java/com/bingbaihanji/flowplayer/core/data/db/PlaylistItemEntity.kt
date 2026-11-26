package com.bingbaihanji.flowplayer.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 播放列表条目。同一播放列表内以 path 唯一，重复加入时会替换旧条目。
 */
@Entity(
    tableName = "playlist_item",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("playlistId"),
        Index(value = ["playlistId", "path"], unique = true),
    ],
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val playlistId: Long,
    val path: String,
    val title: String,
    val mimeType: String,
    val durationMs: Long,
    val size: Long,
    val orderIndex: Int,
    val addedAt: Long,
)
