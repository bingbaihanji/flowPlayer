package com.bingbaihanji.flowplayer.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PlaylistItemEntity): Long

    @Query("SELECT * FROM playlist_item WHERE playlistId = :playlistId ORDER BY orderIndex ASC, id ASC")
    fun observeByPlaylist(playlistId: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_item WHERE playlistId = :playlistId ORDER BY orderIndex ASC, id ASC")
    suspend fun getByPlaylist(playlistId: Long): List<PlaylistItemEntity>

    @Query("SELECT * FROM playlist_item WHERE id = :itemId")
    suspend fun findById(itemId: Long): PlaylistItemEntity?

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM playlist_item WHERE playlistId = :playlistId")
    suspend fun maxOrderIndex(playlistId: Long): Int

    @Query("UPDATE playlist_item SET orderIndex = :orderIndex WHERE id = :itemId")
    suspend fun updateOrderIndex(itemId: Long, orderIndex: Int)

    @Query("DELETE FROM playlist_item WHERE id = :itemId")
    suspend fun deleteById(itemId: Long)
}
