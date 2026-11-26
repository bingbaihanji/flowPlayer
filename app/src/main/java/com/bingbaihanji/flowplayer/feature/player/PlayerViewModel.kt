package com.bingbaihanji.flowplayer.feature.player

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.bingbaihanji.flowplayer.core.data.repository.HistoryRepository
import com.bingbaihanji.flowplayer.core.data.settings.AppSettings
import com.bingbaihanji.flowplayer.core.data.settings.SettingsRepository
import com.bingbaihanji.flowplayer.core.data.settings.SpeedMode
import com.bingbaihanji.flowplayer.core.player.PlayMode
import com.bingbaihanji.flowplayer.core.player.PlaybackRequest
import com.bingbaihanji.flowplayer.core.player.PlayerContract
import com.bingbaihanji.flowplayer.core.player.PlayerController
import com.bingbaihanji.flowplayer.core.player.PlayerPlaybackState
import com.bingbaihanji.flowplayer.core.player.PlayerQueueItem
import com.bingbaihanji.flowplayer.core.player.PlayerTrack
import com.bingbaihanji.flowplayer.core.player.TrackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Media3 全屏播放页的 ViewModel。
 *
 * 职责：
 * - 持有 [PlayerController]，把播放内核状态转发给 UI；
 * - 连接控制器并在初始化时提交播放队列；
 * - 播放历史写入（播放中每 5 秒、暂停、切歌、手动保存）；
 * - 定时停止倒计时；
 * - 外挂字幕导入与用户提示消息；
 * - 实现 [PlayerUiSession]，失败时可切到 LibVLC 兼容模式。
 *
 * 注意：播放内核的 MediaController 只通过 [renderPlayer] 暴露给 PlayerView 渲染，UI 不接触 ExoPlayer。
 */
class PlayerViewModel(
    application: Application,
    private val request: PlaybackRequest,
) : AndroidViewModel(application), PlayerUiSession {

    private val controller = PlayerController(application)
    private val historyRepository = HistoryRepository.from(application)
    private val settingsRepository = SettingsRepository.from(application)

    /**
     * 历史写入使用独立作用域：退出播放页时 ViewModel 很快被清理，避免正在进行的 Room 写入被取消。
     * 写入任务都很短，完成后协程结束，不会长期持有引用。
     */
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val historyMutex = Mutex()

    override val state: StateFlow<PlayerPlaybackState> = controller.state
    val renderPlayer: StateFlow<Player?> = controller.renderPlayer

    /** 本次播放的完整队列（文件库/历史/播放列表传入）。 */
    override val queue: List<PlayerQueueItem> = request.items

    /** 队列标题，可能为空。 */
    override val queueTitle: String = request.queueTitle

    /** 控制器连接完成前用于显示标题。 */
    override val initialTitle: String = request.queueTitle.ifBlank {
        request.items.getOrNull(request.safeStartIndex)?.title.orEmpty()
    }

    /** Media3 会话失败时允许切到 LibVLC 兼容模式。 */
    override val canFallbackToVlc: Boolean = true

    /** 设置读取完成前使用默认“常规倍数”，完成后再更新为设置值。 */
    private val _preferredSpeedMode = MutableStateFlow(SpeedMode.NORMAL)
    override val preferredSpeedMode: StateFlow<SpeedMode> = _preferredSpeedMode.asStateFlow()

    private val _isLocked = MutableStateFlow(false)
    override val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _timerRemainingMs = MutableStateFlow<Long?>(null)
    override val timerRemainingMs: StateFlow<Long?> = _timerRemainingMs.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    override val messages = messageChannel.receiveAsFlow()

    private var sleepTimerJob: Job? = null
    private var lastObservedState: PlayerPlaybackState? = null
    private var boostOriginalSpeed: Float? = null

    /** stopPlayback 后不再提交延迟完成的初始队列，避免退出瞬间又起播。 */
    private var playbackStopped = false

    init {
        controller.connect()
        startPlayback()
    }

    /**
     * 在提交播放队列前读取设置与历史：
     * - 设置读取失败时退回 [AppSettings] 默认值；
     * - 续播读取失败只影响起始进度，不阻塞播放；
     * - DataStore/Room 的挂起读取在 IO 线程完成，主线程只做控制器调用。
     */
    private fun startPlayback() {
        viewModelScope.launch {
            val (settings, resolvedRequest) = withContext(Dispatchers.IO) {
                val loaded = runCatching { settingsRepository.settings.first() }
                    .onFailure { Log.w(TAG, "读取播放设置失败，使用默认值", it) }
                    .getOrNull() ?: AppSettings()
                loaded to request.withResumePosition(
                    historyRepository = historyRepository,
                    continueLastPosition = loaded.continueLastPosition,
                )
            }
            if (playbackStopped) return@launch
            _preferredSpeedMode.value = settings.speedMode
            controller.setPlayMode(settings.playMode)
            controller.playQueue(resolvedRequest)
            // 队列提交后再观察，避免把上一会话残留状态误记为本次播放历史。
            observeForHistory()
        }
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
        playbackStopped = true
        recordSnapshot(controller.state.value)
        controller.stop()
    }

    /**
     * 为 LibVLC 兼容模式构造请求：保留原队列/标题/历史设置，从当前条目与进度继续。
     */
    fun buildCompatibilityRequest(): PlaybackRequest {
        val snapshot = controller.state.value
        return PlaybackRequest(
            items = request.items,
            startIndex = snapshot.currentIndex
                .coerceIn(0, (request.items.size - 1).coerceAtLeast(0)),
            startPositionMs = snapshot.positionMs.coerceAtLeast(0L),
            queueTitle = request.queueTitle,
            saveHistory = request.saveHistory,
        )
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

    /**
     * 播放页切换模式先作用于当前控制器，再异步写回“默认播放模式”；
     * 写入失败只影响下次进入的默认值，不影响本次会话。
     */
    override fun setPlayMode(mode: PlayMode) {
        controller.setPlayMode(mode)
        viewModelScope.launch {
            runCatching { settingsRepository.setPlayMode(mode) }
                .onFailure { Log.w(TAG, "保存默认播放模式失败", it) }
        }
    }

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

    fun toggleLock() {
        _isLocked.value = !_isLocked.value
    }

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

    // ---------------------------------------------------------------- 生命周期

    override fun onCleared() {
        sleepTimerJob?.cancel()
        controller.release()
        super.onCleared()
    }

    private fun displayNameOf(path: String): String {
        return path.substringAfterLast('/').ifBlank { path }
    }

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val HISTORY_TICK_MS = 5_000L
        private const val BOOST_SPEED = 3.0f

        fun factory(application: Application, request: PlaybackRequest): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
                        "Unknown ViewModel class: ${modelClass.name}"
                    }
                    return PlayerViewModel(application, request) as T
                }
            }
    }
}
