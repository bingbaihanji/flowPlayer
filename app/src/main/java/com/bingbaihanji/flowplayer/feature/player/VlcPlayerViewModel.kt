package com.bingbaihanji.flowplayer.feature.player

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bingbaihanji.flowplayer.core.data.repository.HistoryRepository
import com.bingbaihanji.flowplayer.core.player.AspectMode
import com.bingbaihanji.flowplayer.core.player.PlayMode
import com.bingbaihanji.flowplayer.core.player.PlaybackRequest
import com.bingbaihanji.flowplayer.core.player.PlayerContract
import com.bingbaihanji.flowplayer.core.player.PlayerPlaybackState
import com.bingbaihanji.flowplayer.core.player.PlayerQueueItem
import com.bingbaihanji.flowplayer.core.player.PlayerTrack
import com.bingbaihanji.flowplayer.core.player.TrackKind
import com.bingbaihanji.flowplayer.core.player.vlc.VlcPlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * LibVLC 兼容播放页的 ViewModel。
 *
 * 与 Media3 的 [PlayerViewModel] 保持同一套 UI 会话契约：历史写入（每 5 秒、暂停、切歌、
 * 手动保存）、定时停止、锁定、长按加速、外挂字幕导入与消息提示。差异仅在底层控制器
 * 和视频视图需要由 Activity 通过 [attachVideo]/[detachVideo] 挂载。
 */
class VlcPlayerViewModel(
    application: Application,
    private val request: PlaybackRequest,
) : AndroidViewModel(application), PlayerUiSession {

    private val controller = VlcPlayerController(application)
    private val historyRepository = HistoryRepository.from(application)

    /**
     * 历史写入使用独立作用域：退出播放页时 ViewModel 很快被清理，避免正在进行的 Room 写入被取消。
     */
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val historyMutex = Mutex()

    override val state: StateFlow<PlayerPlaybackState> = controller.state

    /** 本次播放的完整队列（文件库/历史/播放列表传入）。 */
    override val queue: List<PlayerQueueItem> = request.items

    /** 队列标题，可能为空。 */
    override val queueTitle: String = request.queueTitle

    /** 控制器连接完成前用于显示标题。 */
    override val initialTitle: String = request.queueTitle.ifBlank {
        request.items.getOrNull(request.safeStartIndex)?.title.orEmpty()
    }

    /** VLC 已是兼容兜底引擎，失败后没有下一级引擎可切。 */
    override val canFallbackToVlc: Boolean = false

    private val _isLocked = MutableStateFlow(false)
    override val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _timerRemainingMs = MutableStateFlow<Long?>(null)
    override val timerRemainingMs: StateFlow<Long?> = _timerRemainingMs.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    override val messages = messageChannel.receiveAsFlow()

    private var sleepTimerJob: Job? = null
    private var lastObservedState: PlayerPlaybackState? = null
    private var boostOriginalSpeed: Float? = null

    init {
        controller.connect()
        controller.playQueue(request)
        observeForHistory()
    }

    // ---------------------------------------------------------------- 历史

    private fun observeForHistory() {
        // 暂停、切歌时补写；切歌前保存上一条的快照。
        viewModelScope.launch {
            controller.state.collect { next ->
                val previous = lastObservedState
                lastObservedState = next
                if (previous == null) return@collect
                if (previous.isPlaying && !next.isPlaying) {
                    recordSnapshot(next)
                }
                if (previous.itemCount > 0 && previous.currentIndex != next.currentIndex) {
                    recordSnapshot(previous)
                }
            }
        }
        // 播放中每 5 秒写一次当前进度。
        viewModelScope.launch {
            while (true) {
                delay(HISTORY_TICK_MS)
                val snapshot = controller.state.value
                if (snapshot.isPlaying) recordSnapshot(snapshot)
            }
        }
    }

    private fun recordSnapshot(snapshot: PlayerPlaybackState) {
        if (!request.saveHistory) return
        if (snapshot.path.isBlank()) return
        val path = snapshot.path
        val title = snapshot.title.ifBlank { displayNameOf(path) }
        val mimeType = snapshot.mimeType
        val durationMs = snapshot.durationMs
        val positionMs = snapshot.positionMs
        historyScope.launch {
            historyMutex.withLock {
                runCatching {
                    historyRepository.record(
                        path = path,
                        title = title,
                        mimeType = mimeType,
                        durationMs = durationMs,
                        positionMs = positionMs,
                    )
                }.onFailure { Log.w(TAG, "写入播放历史失败：$path", it) }
            }
        }
    }

    /** Activity.onStop 与退出前调用，立即写入当前进度。 */
    override fun saveHistoryNow() {
        if (!request.saveHistory) return
        recordSnapshot(controller.state.value)
    }

    /** 停止播放并保存进度；Activity 随后 finish。 */
    override fun stopPlayback() {
        recordSnapshot(controller.state.value)
        controller.stop()
    }

    // ---------------------------------------------------------------- 播放控制

    override fun togglePlayPause() = controller.togglePlayPause()

    fun play() = controller.play()

    fun pause() = controller.pause()

    override fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    override fun seekBy(deltaMs: Long) = controller.seekBy(deltaMs)

    override fun playNext() = controller.playNext()

    override fun playPrevious() = controller.playPrevious()

    override fun selectQueueItem(index: Int) = controller.selectQueueItem(index)

    override fun setSpeed(speed: Float) = controller.setSpeed(speed)

    override fun setPlayMode(mode: PlayMode) = controller.setPlayMode(mode)

    /**
     * 统一轨道选择入口：字幕走 selectTextTrack，音频/视频分别走内核的对应接口。
     */
    override fun selectTrack(track: PlayerTrack) {
        when (track.kind) {
            TrackKind.TEXT -> controller.selectTextTrack(track)
            TrackKind.AUDIO -> controller.selectAudioTrack(track)
            TrackKind.VIDEO -> controller.selectVideoTrack(track)
        }
    }

    /** 关闭内嵌/外挂字幕轨。 */
    override fun disableSubtitles() = controller.selectTextTrack(null)

    /** 长按加速：记录当前倍速并临时切到 3.0x。 */
    override fun startBoost() {
        if (boostOriginalSpeed != null) return
        boostOriginalSpeed = controller.state.value.speed
        controller.setSpeed(BOOST_SPEED)
    }

    /** 松手恢复长按前的倍速。 */
    override fun endBoost() {
        val original = boostOriginalSpeed ?: return
        boostOriginalSpeed = null
        controller.setSpeed(original)
    }

    override fun retry() = controller.retry()

    // ---------------------------------------------------------------- 锁定

    override fun setLocked(locked: Boolean) {
        _isLocked.value = locked
    }

    // ---------------------------------------------------------------- 定时停止

    override fun startSleepTimer(minutes: Int) {
        if (minutes <= 0) return
        sleepTimerJob?.cancel()
        val totalMs = minutes * 60_000L
        _timerRemainingMs.value = totalMs
        sleepTimerJob = viewModelScope.launch {
            var remaining = totalMs
            while (remaining > 0) {
                delay(1_000L)
                remaining -= 1_000L
                _timerRemainingMs.value = remaining.coerceAtLeast(0L)
            }
            _timerRemainingMs.value = null
            controller.pause()
            messageChannel.trySend("定时停止：已暂停播放")
        }
    }

    override fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _timerRemainingMs.value = null
    }

    // ---------------------------------------------------------------- 外挂字幕

    override fun importSubtitle(uri: Uri) {
        viewModelScope.launch {
            val displayName = withContext(Dispatchers.IO) { queryDisplayName(uri) }
                ?: uri.lastPathSegment.orEmpty()
            val mimeType = PlayerContract.subtitleMimeFor(displayName)
                ?: PlayerContract.subtitleMimeFor(uri.lastPathSegment.orEmpty())
                ?: resolverSubtitleMime(uri)
            if (mimeType == null) {
                messageChannel.trySend("字幕导入失败：仅支持 SRT/VTT/ASS/SSA")
                return@launch
            }
            controller.setExternalSubtitle(uri, mimeType) { result ->
                val message = if (result.isSuccess) {
                    "字幕导入成功"
                } else {
                    "字幕导入失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                }
                messageChannel.trySend(message)
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 文件名没有扩展名时，用内容提供者报告的 MIME 兜底。 */
    private fun resolverSubtitleMime(uri: Uri): String? {
        val mime = runCatching {
            getApplication<Application>().contentResolver.getType(uri)
        }.getOrNull()?.lowercase() ?: return null
        return when (mime) {
            "application/x-subrip", "application/srt", "text/srt" -> "application/x-subrip"
            "text/vtt", "application/x-vtt" -> "text/vtt"
            "text/x-ssa", "application/x-ssa", "text/ass" -> "text/x-ssa"
            "application/ttml+xml", "text/ttml" -> "application/ttml+xml"
            else -> null
        }
    }

    // ---------------------------------------------------------------- 视频视图

    /** 由 [VlcVideoSurface] 在进入组合时挂载 VLC 视频布局。 */
    fun attachVideo(layout: VLCVideoLayout) = controller.attachVideo(layout)

    /** 由 [VlcVideoSurface] 在离开组合时解除挂载。 */
    fun detachVideo() = controller.detachVideo()

    /** 画面比例：16:9 / 4:3 由外层容器约束，其余交给 VLC scale。 */
    fun setAspectMode(mode: AspectMode) = controller.setAspectMode(mode)

    // ---------------------------------------------------------------- 生命周期

    override fun onCleared() {
        // 退出兜底：即使 Activity.onStop 未执行，也把最后进度交给独立 IO 作用域写入。
        recordSnapshot(controller.state.value)
        sleepTimerJob?.cancel()
        controller.release()
        super.onCleared()
    }

    private fun displayNameOf(path: String): String {
        return path.substringAfterLast('/').ifBlank { path }
    }

    companion object {
        private const val TAG = "VlcPlayerViewModel"
        private const val HISTORY_TICK_MS = 5_000L
        private const val BOOST_SPEED = 3.0f

        fun factory(application: Application, request: PlaybackRequest): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(VlcPlayerViewModel::class.java)) {
                        "Unknown ViewModel class: ${modelClass.name}"
                    }
                    return VlcPlayerViewModel(application, request) as T
                }
            }
    }
}
