package com.bingbaihanji.flowplayer.feature.player

import android.net.Uri
import com.bingbaihanji.flowplayer.core.data.settings.SpeedMode
import com.bingbaihanji.flowplayer.core.player.PlayMode
import com.bingbaihanji.flowplayer.core.player.PlayerPlaybackState
import com.bingbaihanji.flowplayer.core.player.PlayerQueueItem
import com.bingbaihanji.flowplayer.core.player.PlayerTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放页对 Media3 / LibVLC 两套内核暴露的统一 UI 会话。
 *
 * 控制层、手势层、菜单只依赖本接口，不感知具体引擎；引擎差异仅在视频 Surface 和
 * 各自的 Activity/ViewModel 装配层。
 */
interface PlayerUiSession {

    val state: StateFlow<PlayerPlaybackState>
    val isLocked: StateFlow<Boolean>
    val timerRemainingMs: StateFlow<Long?>

    /** 设置页配置的倍速档位模式；仅作为本次菜单的初始档位，用户可在会话内临时切换。 */
    val preferredSpeedMode: StateFlow<SpeedMode>
    val messages: Flow<String>

    val queue: List<PlayerQueueItem>
    val queueTitle: String
    val initialTitle: String

    /** Media3 失败时可切到 LibVLC 兼容模式；VLC 会话为 false。 */
    val canFallbackToVlc: Boolean

    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    fun playNext()
    fun playPrevious()
    fun selectQueueItem(index: Int)

    fun setSpeed(speed: Float)
    fun setPlayMode(mode: PlayMode)

    fun selectTrack(track: PlayerTrack)
    fun disableSubtitles()

    fun startSleepTimer(minutes: Int)
    fun cancelSleepTimer()

    fun setLocked(locked: Boolean)
    fun startBoost()
    fun endBoost()

    fun retry()
    fun saveHistoryNow()
    fun stopPlayback()
    fun importSubtitle(uri: Uri)
}
