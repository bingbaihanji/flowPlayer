package com.bingbaihanji.flowplayer.feature.player

import com.bingbaihanji.flowplayer.core.data.repository.HistoryRepository
import com.bingbaihanji.flowplayer.core.player.PlaybackRequest

/** 距离结尾不足 5 秒时视为已看完，下次从头播放。 */
private const val NEAR_END_THRESHOLD_MS = 5_000L

/**
 * 解析本次播放的实际起始进度：
 * - 请求自带进度（历史页点击、Media3 兼容模式切换）优先，直接返回原请求；
 * - 开启“继续上次位置”时，按当前条目 path 读取 Room 历史；
 * - 历史进度接近结尾（时长有效且剩余不足 5 秒）或进度无效时从 0 开始；
 * - 历史读取失败只放弃续播，不影响随后正常播放。
 */
internal suspend fun PlaybackRequest.withResumePosition(
    historyRepository: HistoryRepository,
    continueLastPosition: Boolean,
): PlaybackRequest {
    if (startPositionMs > 0L || !continueLastPosition) return this
    val currentPath = items.getOrNull(safeStartIndex)?.path.orEmpty()
    if (currentPath.isBlank()) return this
    val history = runCatching { historyRepository.findHistory(currentPath) }.getOrNull()
        ?: return this
    val resumeMs = history.positionMs
    if (resumeMs <= 0L) return this
    if (history.durationMs > 0L && resumeMs >= history.durationMs - NEAR_END_THRESHOLD_MS) {
        return this
    }
    return copy(startPositionMs = resumeMs)
}
