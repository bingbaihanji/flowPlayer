package com.bingbaihanji.flowplayer.core.data.repository

import android.content.Context
import com.bingbaihanji.flowplayer.core.data.db.AppDatabase
import com.bingbaihanji.flowplayer.core.data.db.PlaybackHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 播放历史数据仓库。M3 播放器通过 [record] 写入进度，本仓库也提供读取/删除/清空能力。
 */
class HistoryRepository(database: AppDatabase) {

    private val historyDao = database.playbackHistoryDao()

    fun observeHistory(): Flow<List<PlaybackHistoryEntity>> = historyDao.observeAll()

    suspend fun findHistory(path: String): PlaybackHistoryEntity? = historyDao.findByPath(path)

    /**
     * 按 path 写入/更新播放历史；lastPlayedAt 取当前时间。
     */
    suspend fun record(
        path: String,
        title: String,
        mimeType: String,
        durationMs: Long,
        positionMs: Long,
    ) {
        historyDao.upsert(
            PlaybackHistoryEntity(
                path = path,
                title = title,
                mimeType = mimeType,
                durationMs = durationMs,
                positionMs = positionMs,
                lastPlayedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun upsert(history: PlaybackHistoryEntity): Long = historyDao.upsert(history)

    suspend fun remove(id: Long) {
        historyDao.deleteById(id)
    }

    /**
     * 文件被删除后清理对应的播放历史。
     */
    suspend fun removeByPath(path: String) {
        historyDao.deleteByPath(path)
    }

    suspend fun clear() {
        historyDao.clear()
    }

    companion object {
        fun from(context: Context): HistoryRepository =
            HistoryRepository(AppDatabase.getInstance(context))
    }
}
