package com.bingbaihanji.flowplayer.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query(
        """
        SELECT p.id AS id,
               p.name AS name,
               p.createdAt AS createdAt,
               p.updatedAt AS updatedAt,
               (SELECT COUNT(*) FROM playlist_item i WHERE i.playlistId = p.id) AS itemCount
        FROM playlist p
        ORDER BY p.updatedAt DESC, p.id DESC
        """
    )
    fun observeSummaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlist WHERE id = :playlistId")
    fun observeById(playlistId: Long): Flow<PlaylistEntity?>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlist SET name = :name, updatedAt = :updatedAt WHERE id = :playlistId")
    suspend fun rename(playlistId: Long, name: String, updatedAt: Long)

    @Query("UPDATE playlist SET updatedAt = :updatedAt WHERE id = :playlistId")
    suspend fun touch(playlistId: Long, updatedAt: Long)

    @Query("DELETE FROM playlist WHERE id = :playlistId")
    suspend fun deleteById(playlistId: Long)
}
