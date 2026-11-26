package com.bingbaihanji.flowplayer.core.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.bingbaihanji.flowplayer.core.data.db.AppDatabase
import com.bingbaihanji.flowplayer.core.data.db.PlaylistEntity
import com.bingbaihanji.flowplayer.core.data.db.PlaylistItemEntity
import com.bingbaihanji.flowplayer.core.data.db.PlaylistSummary
import kotlinx.coroutines.flow.Flow

/**
 * 播放列表数据仓库：列表 CRUD、条目增删与排序。
 */
class PlaylistRepository(private val database: AppDatabase) {

    private val playlistDao = database.playlistDao()
    private val itemDao = database.playlistItemDao()

    fun observePlaylists(): Flow<List<PlaylistSummary>> = playlistDao.observeSummaries()

    fun observePlaylist(playlistId: Long): Flow<PlaylistEntity?> =
        playlistDao.observeById(playlistId)

    fun observeItems(playlistId: Long): Flow<List<PlaylistItemEntity>> =
        itemDao.observeByPlaylist(playlistId)

    suspend fun createPlaylist(name: String): Long {
        val now = System.currentTimeMillis()
        val normalizedName = name.trim().ifBlank { DEFAULT_PLAYLIST_NAME }
        return playlistDao.insert(
            PlaylistEntity(
                name = normalizedName,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun renamePlaylist(playlistId: Long, name: String) {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) return
        playlistDao.rename(playlistId, normalizedName, System.currentTimeMillis())
    }

    suspend fun deletePlaylist(playlistId: Long) {
        playlistDao.deleteById(playlistId)
    }

    /**
     * 向播放列表末尾添加条目。同一列表内 path 重复时替换旧条目。
     */
    suspend fun addItem(
        playlistId: Long,
        path: String,
        title: String = path.substringAfterLast('/'),
        mimeType: String = "",
        durationMs: Long = 0L,
        size: Long = 0L,
    ): Long = database.withTransaction {
        val now = System.currentTimeMillis()
        val orderIndex = itemDao.maxOrderIndex(playlistId) + 1
        val itemId = itemDao.insert(
            PlaylistItemEntity(
                playlistId = playlistId,
                path = path,
                title = title.ifBlank { path.substringAfterLast('/') },
                mimeType = mimeType,
                durationMs = durationMs,
                size = size,
                orderIndex = orderIndex,
                addedAt = now,
            ),
        )
        playlistDao.touch(playlistId, now)
        itemId
    }

    suspend fun removeItem(itemId: Long) {
        database.withTransaction {
            val item = itemDao.findById(itemId) ?: return@withTransaction
            itemDao.deleteById(itemId)
            reindex(item.playlistId)
            playlistDao.touch(item.playlistId, System.currentTimeMillis())
        }
    }

    /**
     * 将条目在列表内上移/下移 [offset] 位（-1 上移，1 下移）。
     */
    suspend fun moveItem(playlistId: Long, itemId: Long, offset: Int) {
        if (offset == 0) return
        database.withTransaction {
            val items = itemDao.getByPlaylist(playlistId)
            val fromIndex = items.indexOfFirst { it.id == itemId }
            if (fromIndex == -1) return@withTransaction
            val toIndex = (fromIndex + offset).coerceIn(0, items.lastIndex)
            if (toIndex == fromIndex) return@withTransaction
            val reordered = items.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
            reordered.forEachIndexed { index, item ->
                if (item.orderIndex != index) {
                    itemDao.updateOrderIndex(item.id, index)
                }
            }
            playlistDao.touch(playlistId, System.currentTimeMillis())
        }
    }

    private suspend fun reindex(playlistId: Long) {
        itemDao.getByPlaylist(playlistId).forEachIndexed { index, item ->
            if (item.orderIndex != index) {
                itemDao.updateOrderIndex(item.id, index)
            }
        }
    }

    companion object {
        private const val DEFAULT_PLAYLIST_NAME = "新建播放列表"

        fun from(context: Context): PlaylistRepository =
            PlaylistRepository(AppDatabase.getInstance(context))
    }
}
