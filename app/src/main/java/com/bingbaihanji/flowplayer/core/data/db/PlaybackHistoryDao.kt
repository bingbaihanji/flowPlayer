package com.bingbaihanji.flowplayer.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackHistoryDao {

    /**
     * path 上有唯一索引，冲突时 REPLACE 等价于按 path upsert。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(history: PlaybackHistoryEntity): Long

    @Query("SELECT * FROM playback_history ORDER BY lastPlayedAt DESC, id DESC")
    fun observeAll(): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT * FROM playback_history WHERE path = :path LIMIT 1")
    suspend fun findByPath(path: String): PlaybackHistoryEntity?

    @Query("DELETE FROM playback_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM playback_history")
    suspend fun clear()
}
