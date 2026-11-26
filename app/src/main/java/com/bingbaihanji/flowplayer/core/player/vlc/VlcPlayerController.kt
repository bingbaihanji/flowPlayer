package com.bingbaihanji.flowplayer.core.player.vlc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.bingbaihanji.flowplayer.core.player.AspectMode
import com.bingbaihanji.flowplayer.core.player.PlayMode
import com.bingbaihanji.flowplayer.core.player.PlaybackRequest
import com.bingbaihanji.flowplayer.core.player.PlayerPlaybackState
import com.bingbaihanji.flowplayer.core.player.PlayerTrack
import com.bingbaihanji.flowplayer.core.player.TrackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * LibVLC 兼容播放控制客户端。
 *
 * 与 Media3 的 PlayerController 对齐：UI 只依赖 [state] 与这里的方法，不直接接触
 * LibVLC。服务通过 [VlcPlaybackService.LocalBinder] 在同进程暴露，视频布局对象
 * （VLCVideoLayout）可以直接传给服务挂载。
 *
 * 连接策略：
 * - [connect] 先 startForegroundService 再 bindService；
 * - 未绑定期间收到的 [playQueue]、[attachVideo]、[setAspectMode]、[setSpeed]、
 *   [setPlayMode] 会缓存，绑定成功后按顺序应用。
 *
 * 所有方法都应在主线程调用；状态收集也固定在主线程。
 */
class VlcPlayerController(context: Context) {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlayerPlaybackState())
    val state: StateFlow<PlayerPlaybackState> = _state.asStateFlow()

    private var service: VlcPlaybackService? = null
    private var connecting = false
    private var bindRequested = false
    private var released = false
    private var stateJob: Job? = null

    /** 绑定前缓存的播放请求。 */
    private var pendingRequest: PlaybackRequest? = null
    private var pendingSpeed: Float? = null
    private var pendingPlayMode: PlayMode? = null
    private var pendingAspectMode: AspectMode? = null

    /** 最新的视频布局；重新绑定时需要重新挂载。 */
    private var videoLayout: VLCVideoLayout? = null

    private val connection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            connecting = false
            if (released) return

            val localBinder = binder as? VlcPlaybackService.LocalBinder
            if (localBinder == null) {
                _state.update {
                    it.copy(connected = false, errorMessage = "无法连接 VLC 播放服务")
                }
                return
            }

            val svc = localBinder.service
            service = svc

            // 先开始镜像服务状态，再应用缓存操作；服务每次状态变化都会回推到 UI。
            stateJob?.cancel()
            stateJob = scope.launch {
                svc.state.collect { latest -> _state.value = latest }
            }

            pendingPlayMode?.let { mode ->
                pendingPlayMode = null
                svc.setPlayMode(mode)
            }
            pendingSpeed?.let { value ->
                pendingSpeed = null
                svc.setSpeed(value)
            }
            pendingAspectMode?.let { mode ->
                pendingAspectMode = null
                svc.setAspectMode(mode)
            }
            // 先挂视频布局再装载队列，避免首帧遗漏。
            videoLayout?.let { layout -> svc.attachVideo(layout) }
            pendingRequest?.let { request ->
                pendingRequest = null
                svc.playQueue(request)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connecting = false
            service = null
            stateJob?.cancel()
            stateJob = null
            _state.update {
                it.copy(
                    connected = false,
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = "播放服务已断开",
                )
            }
        }
    }

    /**
     * 连接服务；幂等，[release] 后不再生效。
     */
    fun connect() {
        if (released || connecting || service != null) return
        connecting = true
        val intent = Intent(appContext, VlcPlaybackService::class.java)
        // startForegroundService 可能因系统后台限制失败；失败时仍尝试绑定，
        // 真正开始播放时服务会再次进入前台。
        runCatching { ContextCompat.startForegroundService(appContext, intent) }
            .onFailure { Log.w(TAG, "启动 VLC 播放服务失败", it) }
        val bound = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            connecting = false
            _state.update {
                it.copy(connected = false, errorMessage = "无法连接 VLC 播放服务")
            }
            return
        }
        bindRequested = true
    }

    /**
     * 断开并释放控制器；后台播放不受影响，释放后不可再次连接。
     */
    fun release() {
        if (released) return
        released = true
        stateJob?.cancel()
        stateJob = null
        pendingRequest = null
        pendingSpeed = null
        pendingPlayMode = null
        pendingAspectMode = null
        videoLayout = null

        val svc = service
        service = null
        // 解除 Surface 引用，避免服务持有已销毁的 Activity 视图；播放本身不中断。
        runCatching { svc?.detachVideo() }
        if (bindRequested) {
            runCatching { appContext.unbindService(connection) }
            bindRequested = false
        }
        scope.cancel()
        _state.update { it.copy(connected = false, isPlaying = false, isBuffering = false) }
    }

    // ---------------------------------------------------------------- 队列与播放控制

    /**
     * 播放一个队列；未绑定时缓存，连接成功后自动执行。
     */
    fun playQueue(request: PlaybackRequest) {
        if (released || request.items.isEmpty()) return
        val svc = service
        if (svc == null) {
            pendingRequest = request
            connect()
            return
        }
        svc.playQueue(request)
    }

    fun togglePlayPause() {
        service?.togglePlayPause()
    }

    fun play() {
        service?.play()
    }

    fun pause() {
        service?.pause()
    }

    fun seekTo(positionMs: Long) {
        val maxPosition = _state.value.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = positionMs.coerceIn(0L, maxPosition)
        // 先乐观更新进度，服务随后会推送准确值。
        _state.update { it.copy(positionMs = target) }
        service?.seekTo(target)
    }

    fun seekBy(deltaMs: Long) {
        seekTo(_state.value.positionMs + deltaMs)
    }

    fun playNext() {
        service?.playNext()
    }

    fun playPrevious() {
        service?.playPrevious()
    }

    fun selectQueueItem(index: Int) {
        service?.selectQueueItem(index)
    }

    fun setSpeed(speed: Float) {
        val normalized = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        _state.update { it.copy(speed = normalized) }
        val svc = service
        if (svc == null) {
            pendingSpeed = normalized
        } else {
            svc.setSpeed(normalized)
        }
    }

    fun setPlayMode(mode: PlayMode) {
        _state.update { it.copy(playMode = mode) }
        val svc = service
        if (svc == null) {
            pendingPlayMode = mode
        } else {
            svc.setPlayMode(mode)
        }
    }

    // ---------------------------------------------------------------- 轨道与字幕

    fun selectAudioTrack(track: PlayerTrack) {
        if (track.kind != TrackKind.AUDIO) return
        service?.selectAudioTrack(track)
    }

    fun selectVideoTrack(track: PlayerTrack) {
        if (track.kind != TrackKind.VIDEO) return
        service?.selectVideoTrack(track)
    }

    /**
     * 选择字幕轨；传 null 关闭字幕。
     */
    fun selectTextTrack(track: PlayerTrack?) {
        if (track != null && track.kind != TrackKind.TEXT) return
        service?.selectTextTrack(track)
    }

    /**
     * 给当前媒体添加外挂字幕。回调在主线程执行。
     */
    fun setExternalSubtitle(uri: Uri, mimeType: String?, onResult: (Result<Unit>) -> Unit = {}) {
        if (released) {
            onResult(Result.failure(IllegalStateException("控制器已释放")))
            return
        }
        val svc = service
        if (svc == null) {
            onResult(Result.failure(IllegalStateException("播放器未连接")))
            return
        }
        runCatching { svc.setExternalSubtitle(uri, mimeType, onResult) }
            .onFailure { error -> onResult(Result.failure(error)) }
    }

    // ---------------------------------------------------------------- 视频视图与比例

    /**
     * 挂载 VLC 视频布局；未绑定时缓存，绑定后自动应用。
     */
    fun attachVideo(layout: VLCVideoLayout) {
        if (released) return
        videoLayout = layout
        service?.attachVideo(layout)
    }

    fun detachVideo() {
        videoLayout = null
        service?.detachVideo()
    }

    fun setAspectMode(mode: AspectMode) {
        val svc = service
        if (svc == null) {
            pendingAspectMode = mode
        } else {
            svc.setAspectMode(mode)
        }
    }

    // ---------------------------------------------------------------- 错误恢复与停止

    fun retry() {
        if (released) return
        _state.update { it.copy(errorMessage = null) }
        val svc = service
        if (svc == null) {
            if (pendingRequest != null) connect()
            return
        }
        svc.retry()
    }

    /**
     * 停止播放并移除通知；UI 通常随后结束播放页。
     */
    fun stop() {
        pendingRequest = null
        service?.stopPlayback()
    }

    private companion object {
        const val TAG = "VlcPlayerController"
        const val MIN_SPEED = 0.1f
        const val MAX_SPEED = 4.0f
    }
}
