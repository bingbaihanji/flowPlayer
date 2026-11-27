package com.bingbaihanji.flowplayer.core.player.vlc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.bingbaihanji.flowplayer.MainActivity
import com.bingbaihanji.flowplayer.R
import com.bingbaihanji.flowplayer.core.player.AspectMode
import com.bingbaihanji.flowplayer.core.player.PlayMode
import com.bingbaihanji.flowplayer.core.player.PlaybackRequest
import com.bingbaihanji.flowplayer.core.player.PlayerPlaybackState
import com.bingbaihanji.flowplayer.core.player.PlayerQueueItem
import com.bingbaihanji.flowplayer.core.player.PlayerTrack
import com.bingbaihanji.flowplayer.core.player.TrackKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.io.FileNotFoundException
import kotlin.random.Random

/**
 * LibVLC 兼容播放服务。
 *
 * 设计要点：
 * - 普通前台 Service（不是 MediaSessionService），通过 [LocalBinder] 把服务直接暴露给
 *   [VlcPlayerController]；UI 与 Service 同进程，视频布局可以直接引用。
 * - 队列由服务手动维护：顺序 / 单曲循环 / 随机三种模式在 [MediaPlayer.Event.EndReached]
 *   中决定下一首、重播当前或结束。
 * - 通知、MediaSessionCompat、音频焦点、耳机拔出监听在这里统一处理；
 *   [stopPlayback] 会移除通知并停止服务。
 * - LibVLC 初始化可能较慢，因此推迟到 [onStartCommand] 里 startForeground 之后执行，
 *   避免前台服务启动超时。
 *
 * 所有 MediaPlayer 操作都约定在主线程执行（控制器与通知回调本身都在主线程）。
 */
class VlcPlaybackService : Service() {

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 服务侧唯一的播放快照，控制器通过收集该 Flow 镜像到 UI。 */
    private val _state = MutableStateFlow(PlayerPlaybackState(connected = true))
    val state: StateFlow<PlayerPlaybackState> = _state.asStateFlow()

    // ---------------------------------------------------------------- LibVLC 与播放状态

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentMedia: Media? = null

    /** content:// 媒体需要保持文件描述符打开，LibVLC 播放期间不能关闭。 */
    private var currentFileDescriptor: ParcelFileDescriptor? = null

    private var queue: List<PlayerQueueItem> = emptyList()
    private var currentIndex = 0
    private var playMode = PlayMode.SEQUENTIAL
    private var speed = 1.0f

    /** 用户希望播放还是暂停；mp.isPlaying 在 Opening/Buffering 阶段并不可靠。 */
    private var playWhenReady = false
    private var buffering = false
    private var ended = false
    private var currentError: String? = null

    private var videoWidth = 0
    private var videoHeight = 0

    /** 首次播放队列/切歌时要恢复的进度，只应用一次。 */
    private var pendingStartPositionMs = 0L
    private var startPositionApplied = true
    private var speedAppliedForCurrent = false

    /** 在“播放已结束”状态下 seek 后再次播放时要回到的位置。 */
    private var pendingResumePositionMs = 0L

    private var tickerScheduled = false

    // ---------------------------------------------------------------- 视频布局与画面比例

    private var aspectMode = AspectMode.FIT

    /** UI 期望挂载的布局；即使当前未成功 attach 也保留，便于 retry 后补挂。 */
    private var videoLayout: VLCVideoLayout? = null
    private var attachedLayout: VLCVideoLayout? = null
    private var videoAttached = false

    // ---------------------------------------------------------------- 通知 / 会话 / 音频

    private var mediaSession: MediaSessionCompat? = null

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusGranted = false

    private var noisyRegistered = false
    private var foregroundStarted = false

    /** 播放期间持有的局部唤醒锁，暂停/停止/自然结束/出错时释放。 */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * 已绑定的控制器数量。onUnbind 只在所有客户端解绑后回调一次，此时直接清零；
     * 用于区分“有人在看 UI（保留结束状态）”与“纯后台播完（结束服务并移除通知）”。
     */
    private var boundClientCount = 0

    // ---------------------------------------------------------------- 生命周期

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createMediaSession()
        audioManager = getSystemService(AudioManager::class.java)
        registerNoisyReceiver()
        // 先把通知挂上前台，保证 startForegroundService 的 5 秒窗口内完成。
        ensureForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须最先执行，避免前台服务启动超时（ANR）。
        ensureForeground()
        if (intent?.action == ACTION_STOP) {
            // 通知栏“停止”：结束后台播放并退出服务，无需初始化 LibVLC。
            stopPlayback()
            return START_NOT_STICKY
        }
        // LibVLC 初始化较慢，放在前台通知建立之后再执行。
        ensureEngine()
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE_PLAY_PAUSE -> togglePlayPause()
            ACTION_PREVIOUS -> playPrevious()
            ACTION_NEXT -> playNext()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        boundClientCount++
        return binder
    }

    /**
     * 所有客户端解绑时：没有媒体且不在播放则顺手结束服务，避免空通知常驻。
     * 暂停中的通知按产品要求保留，因此不在这里结束。
     */
    override fun onUnbind(intent: Intent?): Boolean {
        // 系统只在最后一个客户端解绑后回调 onUnbind，因此直接清零。
        boundClientCount = 0
        if (!playWhenReady && (queue.isEmpty() || ended || currentError != null)) {
            removeForegroundAndStop()
        }
        return super.onUnbind(intent)
    }

    /**
     * 用户从最近任务划掉应用：播放中继续后台播放，否则停止并结束服务。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // 只要用户意图是播放（含缓冲中）就保留后台；暂停/结束/出错则停止并结束服务。
        if (playWhenReady) {
            super.onTaskRemoved(rootIntent)
            return
        }
        stopPlayback()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        tickerScheduled = false
        runCatching { mediaPlayer?.setEventListener(null) }
        runCatching { mediaPlayer?.detachViews() }
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        videoAttached = false
        attachedLayout = null
        videoLayout = null
        runCatching { currentMedia?.release() }
        currentMedia = null
        closeCurrentFileDescriptor()
        runCatching { libVLC?.release() }
        libVLC = null
        mediaSession?.let { session ->
            runCatching { session.setActive(false) }
            runCatching { session.release() }
        }
        mediaSession = null
        if (noisyRegistered) {
            runCatching { unregisterReceiver(noisyReceiver) }
            noisyRegistered = false
        }
        abandonAudioFocus()
        releaseWakeLock()
        if (foregroundStarted) {
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            foregroundStarted = false
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 对外播放 API

    /**
     * 用新队列覆盖播放，并从 [PlaybackRequest.startPositionMs] 开始播放。
     */
    fun playQueue(request: PlaybackRequest) {
        if (request.items.isEmpty()) return
        queue = request.items
        currentIndex = request.safeStartIndex
        currentError = null
        ended = false
        loadItem(currentIndex, request.startPositionMs, autoPlay = true)
    }

    fun togglePlayPause() {
        if (playWhenReady) pause() else play()
    }

    fun play() {
        if (queue.isEmpty()) return
        if (!ensureEngine()) return
        val mp = mediaPlayer ?: return
        val playerState = mp.getPlayerState()
        if (ended || pendingResumePositionMs > 0L ||
            playerState == IMedia.State.Ended ||
            playerState == IMedia.State.Stopped ||
            playerState == IMedia.State.Error ||
            !mp.hasMedia()
        ) {
            // 播放结束后再次点击播放，或媒体尚未装载时，重新装载当前项。
            loadItem(currentIndex, pendingResumePositionMs, autoPlay = true)
            return
        }
        playWhenReady = true
        ensureForeground()
        requestAudioFocus()
        val started = runCatching { mp.play() }
        if (started.isFailure) {
            val error = started.exceptionOrNull() ?: IllegalStateException()
            Log.e(TAG, "继续播放失败", error)
            currentError = "播放失败：${describeThrowable(error)}"
            playWhenReady = false
            buffering = false
            abandonAudioFocus()
            releaseWakeLock()
            publishState(updateNotification = true)
            return
        }
        acquireWakeLock()
        publishState(updateNotification = true)
        scheduleTicker()
    }

    fun pause() {
        playWhenReady = false
        buffering = false
        runCatching { mediaPlayer?.pause() }
        mainHandler.removeCallbacks(tickerRunnable)
        tickerScheduled = false
        abandonAudioFocus()
        // 暂停后不再需要保持 CPU 唤醒
        releaseWakeLock()
        publishState(updateNotification = true)
    }

    fun seekTo(positionMs: Long) {
        val mp = mediaPlayer ?: return
        if (queue.isEmpty()) return
        val rawDuration = mp.getLength().takeIf { it > 0L }
            ?: queue.getOrNull(currentIndex)?.durationMs?.takeIf { it > 0L }
        val target = if (rawDuration != null) {
            positionMs.coerceIn(0L, rawDuration)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        val wasEnded = ended || mp.getPlayerState() == IMedia.State.Ended
        if (wasEnded) {
            // 结束后 VLC 的输入已经停止，直接 seek 无效；记录位置，下次 play() 重新装载。
            ended = false
            pendingResumePositionMs = target
            _state.update { it.copy(positionMs = target, isEnded = false) }
            syncMediaSession(updateMetadata = false)
            refreshNotification()
            return
        }
        runCatching { mp.setTime(target, false) }
        _state.update { it.copy(positionMs = target) }
        syncMediaSession(updateMetadata = false)
    }

    /**
     * 手动下一首。暂停状态下切歌保持暂停，已结束状态下切歌自动播放（与 Media3 内核一致）。
     */
    fun playNext() {
        if (queue.isEmpty()) return
        val shouldPlay = playWhenReady || ended
        val target = when (playMode) {
            PlayMode.SEQUENTIAL -> currentIndex + 1
            PlayMode.REPEAT_ONE -> if (currentIndex + 1 < queue.size) currentIndex + 1 else currentIndex
            PlayMode.SHUFFLE -> randomOtherIndex()
            PlayMode.PAUSE_AT_END -> currentIndex + 1
        }
        if (target !in queue.indices) return
        loadItem(target, 0L, autoPlay = shouldPlay)
    }

    /**
     * 手动上一首。顺序模式第一首不响应；单曲循环第一首从头重播。
     */
    fun playPrevious() {
        if (queue.isEmpty()) return
        val shouldPlay = playWhenReady || ended
        val target = when (playMode) {
            PlayMode.SEQUENTIAL -> currentIndex - 1
            PlayMode.REPEAT_ONE -> if (currentIndex > 0) currentIndex - 1 else currentIndex
            PlayMode.SHUFFLE -> randomOtherIndex()
            PlayMode.PAUSE_AT_END -> currentIndex - 1
        }
        if (target !in queue.indices) return
        loadItem(target, 0L, autoPlay = shouldPlay)
    }

    fun selectQueueItem(index: Int) {
        if (index !in queue.indices) return
        loadItem(index, 0L, autoPlay = true)
    }

    fun setSpeed(speed: Float) {
        val normalized = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        this.speed = normalized
        mediaPlayer?.let { mp -> runCatching { mp.setRate(normalized) } }
        _state.update { it.copy(speed = normalized) }
        syncMediaSession(updateMetadata = false)
    }

    fun setPlayMode(mode: PlayMode) {
        playMode = mode
        publishState(updateNotification = false)
    }

    fun selectAudioTrack(track: PlayerTrack) {
        if (track.kind != TrackKind.AUDIO) return
        val mp = mediaPlayer ?: return
        val applied = runCatching { mp.setAudioTrack(track.trackIndex) }.getOrDefault(false)
        if (!applied) Log.w(TAG, "切换音轨失败：trackIndex=${track.trackIndex}")
        publishState(updateNotification = false)
    }

    fun selectVideoTrack(track: PlayerTrack) {
        if (track.kind != TrackKind.VIDEO) return
        val mp = mediaPlayer ?: return
        val applied = runCatching { mp.setVideoTrack(track.trackIndex) }.getOrDefault(false)
        if (!applied) Log.w(TAG, "切换视频轨失败：trackIndex=${track.trackIndex}")
        refreshVideoSize()
        publishState(updateNotification = false)
    }

    /**
     * 选择字幕轨；[track] 为 null 等价于 setSpuTrack(-1)，关闭字幕。
     */
    fun selectTextTrack(track: PlayerTrack?) {
        if (track != null && track.kind != TrackKind.TEXT) return
        val mp = mediaPlayer ?: return
        val trackId = if (track == null) -1 else track.trackIndex
        val applied = runCatching { mp.setSpuTrack(trackId) }.getOrDefault(false)
        if (!applied) Log.w(TAG, "切换字幕轨失败：trackIndex=$trackId")
        publishState(updateNotification = false)
    }

    /**
     * 为当前媒体挂载外挂字幕。
     *
     * addSlave 的 type 参数 0 即 IMedia.Slave.Type.Subtitle（已按 AAR 常量确认），
     * select=true 让 LibVLC 自动选中新字幕；成功后会通过 ES 事件刷新轨道列表。
     */
    fun setExternalSubtitle(uri: Uri, mimeType: String?, onResult: (Result<Unit>) -> Unit = {}) {
        val mp = mediaPlayer
        if (mp == null || !mp.hasMedia() || queue.isEmpty()) {
            onResult(Result.failure(IllegalStateException("播放器未就绪，请先开始播放")))
            return
        }
        if (uri.toString().isBlank()) {
            onResult(Result.failure(IllegalArgumentException("字幕地址无效")))
            return
        }
        Log.d(TAG, "导入外挂字幕：uri=$uri, mime=$mimeType")
        val success = runCatching {
            val playableUri = if (uri.scheme == "content") {
                copySubtitleToCache(uri, mimeType)
            } else {
                uri
            }
            mp.addSlave(IMedia.Slave.Type.Subtitle, playableUri, true)
        }.getOrDefault(false)
        if (!success) {
            onResult(Result.failure(IllegalStateException("字幕加载失败：格式可能不受支持")))
            return
        }
        runCatching {
            val spuTracks = mp.spuTracks
            Log.d(
                TAG,
                "外挂字幕已添加：spuCount=${mp.spuTracksCount}, current=${mp.spuTrack}, " +
                    "tracks=${spuTracks?.joinToString { "${it.id}:${it.name}" }}",
            )
        }.onFailure { Log.d(TAG, "读取 SPU 轨失败", it) }
        // LibVLC 在播放中 addSlave 后会重建视频输出，部分设备上画面会变黑；重新挂载视图恢复渲染。
        val layout = attachedLayout
        if (layout != null) {
            runCatching { mp.detachViews() }
            videoAttached = false
            attachedLayout = null
            attachVideo(layout)
        }
        publishState(updateNotification = false)
        onResult(Result.success(Unit))
    }

    /**
     * LibVLC 的 addSlave 对 content:// 支持不稳定；把 SAF 选择的外挂字幕复制到 cache，
     * 再用普通 file URI 加载。字幕体积很小，保留缓存即可。
     */
    private fun copySubtitleToCache(uri: Uri, mimeType: String?): Uri {
        val extension = when (mimeType) {
            "text/vtt" -> "vtt"
            "text/x-ssa" -> "ass"
            "application/ttml+xml" -> "ttml"
            else -> "srt"
        }
        val dir = File(cacheDir, "subtitles").apply { mkdirs() }
        val target = File(dir, "subtitle_${SystemClock.elapsedRealtime()}.$extension")
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw FileNotFoundException("无法读取字幕文件：$uri")
        return Uri.fromFile(target)
    }

    fun retry() {
        currentError = null
        if (queue.isEmpty()) {
            publishState(updateNotification = true)
            return
        }
        if (!ensureEngine()) return
        // 附着失败/引擎失败后重试时，若视频布局还在则补挂。
        val layout = videoLayout
        if (layout != null && !videoAttached) {
            attachVideo(layout)
        }
        val lastPosition = if (ended) {
            pendingResumePositionMs
        } else {
            mediaPlayer?.getTime()?.coerceAtLeast(0L) ?: 0L
        }
        ended = false
        loadItem(currentIndex, lastPosition, autoPlay = true)
    }

    /**
     * 停止播放：保留队列信息，移除前台通知并结束服务（若仍被绑定则等到解绑后销毁）。
     */
    fun stopPlayback() {
        mainHandler.removeCallbacks(tickerRunnable)
        tickerScheduled = false
        playWhenReady = false
        buffering = false
        ended = false
        currentError = null
        pendingResumePositionMs = 0L
        pendingStartPositionMs = 0L
        startPositionApplied = true
        runCatching { mediaPlayer?.stop() }
        publishState(updateNotification = false)
        abandonAudioFocus()
        releaseWakeLock()
        removeForegroundAndStop()
    }

    // ---------------------------------------------------------------- 视频布局 / 画面比例

    /**
     * 把 UI 的 VLCVideoLayout 挂到 MediaPlayer。重复挂载同一布局只重新应用比例。
     */
    fun attachVideo(layout: VLCVideoLayout) {
        videoLayout = layout
        if (!ensureEngine()) return
        val mp = mediaPlayer ?: return
        if (videoAttached) {
            if (attachedLayout === layout) {
                applyVideoScale()
                return
            }
            runCatching { mp.detachViews() }
            videoAttached = false
            attachedLayout = null
        }
        // 第 3 个参数 subtitles=true 创建独立的字幕 Surface。
        // 第 4 个参数是 textureView，false 表示使用 SurfaceView 渲染视频（TextureView 分支不会创建字幕 Surface）。
        val result = runCatching { mp.attachViews(layout, null, true, false) }
        if (result.isFailure) {
            val error = result.exceptionOrNull() ?: IllegalStateException()
            Log.e(TAG, "挂载视频布局失败", error)
            videoAttached = false
            attachedLayout = null
            currentError = "视频画面初始化失败：${describeThrowable(error)}"
            publishState(updateNotification = true)
            return
        }
        videoAttached = true
        attachedLayout = layout
        val subtitleView: android.view.View? = layout.findViewById(org.videolan.R.id.surface_subtitles)
        Log.d(TAG, "字幕 Surface 挂载：${subtitleView?.let { "${it.javaClass.simpleName}:${it.width}x${it.height}" }}")
        layout.post {
            val view = layout.findViewById<android.view.View>(org.videolan.R.id.surface_subtitles)
            Log.d(
                TAG,
                "字幕 Surface 布局：${view?.let { "${it.width}x${it.height}, " +
                    "lp=${it.layoutParams?.width}x${it.layoutParams?.height}" }}",
            )
        }
        applyVideoScale()
    }

    fun detachVideo() {
        videoLayout = null
        attachedLayout = null
        videoAttached = false
        runCatching { mediaPlayer?.detachViews() }
    }

    fun setAspectMode(mode: AspectMode) {
        aspectMode = mode
        applyVideoScale()
    }

    private fun applyVideoScale() {
        if (!videoAttached) return
        val mp = mediaPlayer ?: return
        val scaleType = when (aspectMode) {
            AspectMode.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            AspectMode.FILL -> MediaPlayer.ScaleType.SURFACE_FILL
            AspectMode.ZOOM -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            AspectMode.RATIO_16_9 -> MediaPlayer.ScaleType.SURFACE_16_9
            AspectMode.RATIO_4_3 -> MediaPlayer.ScaleType.SURFACE_4_3
        }
        runCatching { mp.setVideoScale(scaleType) }
            .onFailure { Log.w(TAG, "设置画面比例失败", it) }
    }

    // ---------------------------------------------------------------- 队列装载与结束处理

    /**
     * 装载队列中的某一项。
     *
     * @param autoPlay false 用于暂停状态下手动切歌：只替换媒体，等用户再次 play。
     */
    private fun loadItem(index: Int, startPositionMs: Long, autoPlay: Boolean) {
        if (index !in queue.indices) return
        if (!ensureEngine()) return
        val mp = mediaPlayer ?: return
        val vlc = libVLC ?: return
        val item = queue[index]

        currentIndex = index
        currentError = null
        ended = false
        buffering = autoPlay
        playWhenReady = autoPlay
        pendingStartPositionMs = startPositionMs.coerceAtLeast(0L)
        startPositionApplied = startPositionMs <= 0L
        pendingResumePositionMs = 0L
        speedAppliedForCurrent = false
        videoWidth = 0
        videoHeight = 0

        if (autoPlay) ensureForeground()

        val loaded = runCatching {
            mp.stop()
            currentMedia?.release()
            currentMedia = null
            closeCurrentFileDescriptor()
            val media = openMedia(vlc, item)
            // AVI 常见 MJPEG 编码，硬件解码在外部字幕/重连时容易失败；这类文件改用软件解码更稳。
            val isAvi = item.path.substringAfterLast('.', "").equals("avi", ignoreCase = true) ||
                item.uri.substringAfterLast('.', "").equals("avi", ignoreCase = true)
            media.setHWDecoderEnabled(!isAvi, false)
            currentMedia = media
            mp.setMedia(media)
        }
        if (loaded.isFailure) {
            val error = loaded.exceptionOrNull() ?: IllegalStateException()
            Log.e(TAG, "加载媒体失败：${item.uri}", error)
            buffering = false
            playWhenReady = false
            currentError = "播放失败：${describeThrowable(error)}"
            releaseWakeLock()
            publishState(updateNotification = true)
            return
        }

        if (autoPlay) {
            val started = runCatching { mp.play() }
            if (started.isFailure) {
                val error = started.exceptionOrNull() ?: IllegalStateException()
                Log.e(TAG, "启动播放失败：${item.uri}", error)
                buffering = false
                playWhenReady = false
                currentError = "播放失败：${describeThrowable(error)}"
                releaseWakeLock()
                publishState(updateNotification = true)
                return
            }
            requestAudioFocus()
            acquireWakeLock()
            scheduleTicker()
        }
        publishState(updateNotification = true)
    }

    /**
     * LibVLC 对 content:// 的支持并不可靠，因此：
     * 1. 文件库已有真实文件路径时优先走 file://；
     * 2. 只有 content URI 时用 ContentResolver 打开 fd，并保持到当前媒体释放；
     * 3. 其余情况交给 LibVLC 自己解析 URI。
     */
    private fun openMedia(vlc: LibVLC, item: PlayerQueueItem): Media {
        val path = item.path
        if (path.isNotBlank() &&
            !path.startsWith("content://") &&
            !path.startsWith("file://")
        ) {
            val file = File(path)
            if (file.exists()) return Media(vlc, Uri.fromFile(file))
        }
        val uri = Uri.parse(item.uri)
        if (uri.scheme == "content") {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
                ?: throw FileNotFoundException("无法打开 content URI：$uri")
            currentFileDescriptor = pfd
            return Media(vlc, pfd.fileDescriptor)
        }
        return Media(vlc, uri)
    }

    private fun closeCurrentFileDescriptor() {
        runCatching { currentFileDescriptor?.close() }
        currentFileDescriptor = null
    }

    /**
     * EndReached：由播放模式决定重播当前、切下一首或真正结束。
     */
    private fun handleEndReached() {
        buffering = false
        mainHandler.removeCallbacks(tickerRunnable)
        tickerScheduled = false
        val nextIndex = when (playMode) {
            PlayMode.SEQUENTIAL ->
                if (currentIndex + 1 < queue.size) currentIndex + 1 else null

            PlayMode.REPEAT_ONE -> currentIndex
            PlayMode.SHUFFLE ->
                if (queue.isEmpty()) null else randomOtherIndex()

            // 播完当前项即暂停，不自动续播下一项。
            PlayMode.PAUSE_AT_END -> null
        }
        if (nextIndex == null || nextIndex !in queue.indices) {
            playWhenReady = false
            ended = true
            abandonAudioFocus()
            releaseWakeLock()
            publishState(updateNotification = true)
            // 纯后台播放自然播完：没有 UI 绑定就结束服务并移除通知；有绑定则保留结束状态供 UI 展示。
            if (boundClientCount == 0) {
                removeForegroundAndStop()
            }
            return
        }
        loadItem(nextIndex, 0L, autoPlay = true)
    }

    private fun randomOtherIndex(): Int {
        if (queue.size <= 1) return currentIndex.coerceIn(0, 0)
        repeat(8) {
            val candidate = Random.nextInt(queue.size)
            if (candidate != currentIndex) return candidate
        }
        // 极小概率连续撞到同一项时强制取下一项，避免死循环。
        return (currentIndex + 1) % queue.size
    }

    // ---------------------------------------------------------------- LibVLC 初始化与事件

    private fun ensureEngine(): Boolean {
        mediaPlayer?.let { return true }
        if (libVLC == null) {
            try {
                // LibVLC 会在构造时向 options 列表追加默认参数，必须传可变 List。
                libVLC = LibVLC(this, arrayListOf("--audio-time-stretch"))
            } catch (t: Throwable) {
                Log.e(TAG, "LibVLC 初始化失败", t)
                currentError = "LibVLC 初始化失败：${describeThrowable(t)}"
                publishState(updateNotification = true)
                return false
            }
        }
        val vlc = libVLC ?: return false
        return try {
            val mp = MediaPlayer(vlc)
            mp.setEventListener(eventListener)
            mediaPlayer = mp
            currentError = null
            true
        } catch (t: Throwable) {
            Log.e(TAG, "创建 LibVLC 播放器失败", t)
            currentError = "LibVLC 初始化失败：${describeThrowable(t)}"
            publishState(updateNotification = true)
            false
        }
    }

    private val eventListener: MediaPlayer.EventListener = object : MediaPlayer.EventListener {
        override fun onEvent(event: MediaPlayer.Event) {
            runCatching {
                when (event.type) {
                    MediaPlayer.Event.Opening -> {
                        buffering = true
                        publishState(updateNotification = true)
                    }

                    MediaPlayer.Event.Playing -> {
                        buffering = false
                        currentError = null
                        applyStartPositionOnce()
                        applySpeedOnce()
                        refreshVideoSize()
                        // 兜底保证实际播放期间持有唤醒锁（例如暂停后恢复的路径）。
                        if (playWhenReady && mediaPlayer?.isPlaying() == true) acquireWakeLock()
                        publishState(updateNotification = true)
                        scheduleTicker()
                    }

                    MediaPlayer.Event.Paused -> {
                        buffering = false
                        // 播放器已进入暂停，释放唤醒锁；用户暂停已在 pause() 中提前释放。
                        releaseWakeLock()
                        publishState(updateNotification = true)
                    }

                    MediaPlayer.Event.Stopped -> {
                        // 换片时我们会先 stop 再 setMedia，这里的 Stopped 不代表用户暂停，避免闪断。
                        if (!playWhenReady) {
                            buffering = false
                            releaseWakeLock()
                            publishState(updateNotification = true)
                        }
                    }

                    MediaPlayer.Event.Buffering -> {
                        val nowBuffering = event.getBuffering() < 100f
                        if (nowBuffering != buffering) {
                            buffering = nowBuffering
                            publishState(updateNotification = true)
                        }
                    }

                    MediaPlayer.Event.LengthChanged -> publishProgress()

                    MediaPlayer.Event.TimeChanged -> publishProgress()

                    MediaPlayer.Event.EndReached -> handleEndReached()

                    MediaPlayer.Event.EncounteredError -> handleEncounteredError()

                    MediaPlayer.Event.Vout -> {
                        refreshVideoSize()
                        publishState(updateNotification = false)
                    }

                    MediaPlayer.Event.ESAdded,
                    MediaPlayer.Event.ESDeleted,
                    MediaPlayer.Event.ESSelected -> {
                        refreshVideoSize()
                        publishState(updateNotification = false)
                    }

                    else -> Unit
                }
            }.onFailure { error ->
                Log.e(TAG, "处理 LibVLC 事件失败：type=${event.type}", error)
                playWhenReady = false
                buffering = false
                currentError = "播放失败：${describeThrowable(error)}"
                releaseWakeLock()
                runCatching { publishState(updateNotification = true) }
            }
        }
    }

    /** 切歌/首播时把起始进度在 Playing 后设置一次；VLC 在准备前 seek 可能被忽略。 */
    private fun applyStartPositionOnce() {
        if (startPositionApplied) return
        startPositionApplied = true
        val target = pendingStartPositionMs
        if (target <= 0L) return
        val applied = runCatching { mediaPlayer?.setTime(target, false) }.getOrNull()
        if (applied != null && applied > 0L) {
            _state.update { it.copy(positionMs = applied) }
        }
    }

    /** 每个新媒体在开始播放后重新应用一次倍速，避免个别编码路径重置。 */
    private fun applySpeedOnce() {
        if (speedAppliedForCurrent) return
        speedAppliedForCurrent = true
        mediaPlayer?.let { mp -> runCatching { mp.setRate(speed) } }
    }

    private fun handleEncounteredError() {
        playWhenReady = false
        buffering = false
        ended = false
        currentError = "播放失败：文件格式不受支持、文件损坏或无法访问"
        mainHandler.removeCallbacks(tickerRunnable)
        tickerScheduled = false
        abandonAudioFocus()
        releaseWakeLock()
        publishState(updateNotification = true)
    }

    /** 从当前视频轨读取画面尺寸；LibVLC 在 Vout 之后才会给出有效值。 */
    private fun refreshVideoSize(): Boolean {
        val mp = mediaPlayer ?: return false
        val track = runCatching { mp.getCurrentVideoTrack() }.getOrNull() ?: return false
        if (track.width <= 0 || track.height <= 0) return false
        val changed = track.width != videoWidth || track.height != videoHeight
        videoWidth = track.width
        videoHeight = track.height
        return changed
    }

    // ---------------------------------------------------------------- 状态快照与轨道映射

    private fun publishState(updateNotification: Boolean = false) {
        _state.value = buildState()
        syncMediaSession(updateMetadata = updateNotification)
        if (updateNotification) refreshNotification()
    }

    private fun publishProgress() {
        val mp = mediaPlayer ?: return
        val position = mp.getTime().coerceAtLeast(0L)
        val rawDuration = mp.getLength()
        val fallbackDuration = queue.getOrNull(currentIndex)?.durationMs ?: 0L
        val duration = if (rawDuration > 0L) rawDuration else fallbackDuration
        _state.update { current ->
            if (current.positionMs == position && current.durationMs == duration) {
                current
            } else {
                current.copy(positionMs = position, durationMs = duration)
            }
        }
    }

    private fun buildState(): PlayerPlaybackState {
        val mp = mediaPlayer
        val item = queue.getOrNull(currentIndex)
        val playing = playWhenReady && mp?.isPlaying() == true
        val rawDuration = mp?.getLength()
        val duration = if (rawDuration != null && rawDuration > 0L) {
            rawDuration
        } else {
            item?.durationMs ?: 0L
        }
        val position = (mp?.getTime() ?: 0L).coerceAtLeast(0L)
        val rate = mp?.getRate()?.takeIf { it > 0f } ?: speed
        val tracks = collectTracks(mp)
        val spuId = runCatching { mp?.getSpuTrack() ?: -1 }.getOrDefault(-1)
        val subtitlesEnabled = spuId >= 0 &&
                tracks.any { it.kind == TrackKind.TEXT && it.selected }

        return PlayerPlaybackState(
            connected = true,
            isPlaying = playing,
            isBuffering = buffering,
            isEnded = ended,
            durationMs = duration,
            positionMs = position,
            // LibVLC 公开 API 不提供可播放缓冲位置，统一按 0 上报。
            bufferedMs = 0L,
            speed = rate,
            playMode = playMode,
            currentIndex = currentIndex.coerceAtLeast(0),
            itemCount = queue.size,
            hasNext = canPlayNext(),
            hasPrevious = canPlayPrevious(),
            title = item?.title.orEmpty(),
            uri = item?.uri.orEmpty(),
            path = item?.path.orEmpty(),
            mimeType = item?.mimeType.orEmpty(),
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            tracks = tracks,
            subtitlesEnabled = subtitlesEnabled,
            errorMessage = currentError,
        )
    }

    private fun canPlayNext(): Boolean = when (playMode) {
        PlayMode.SEQUENTIAL -> currentIndex + 1 < queue.size
        PlayMode.REPEAT_ONE -> queue.isNotEmpty()
        PlayMode.SHUFFLE -> queue.size > 1
        PlayMode.PAUSE_AT_END -> currentIndex + 1 < queue.size
    }

    private fun canPlayPrevious(): Boolean = when (playMode) {
        PlayMode.SEQUENTIAL -> currentIndex > 0
        PlayMode.REPEAT_ONE -> queue.isNotEmpty()
        PlayMode.SHUFFLE -> queue.size > 1
        PlayMode.PAUSE_AT_END -> currentIndex > 0
    }

    /**
     * 把 LibVLC 的音/视频/字幕轨道描述映射成统一轨道模型：
     * groupIndex 恒为 0，trackIndex 直接使用 LibVLC 的 track id（选择时原样传回）。
     * id 与 Media3 内核保持同样的 “类型:组:索引” 形态，方便 UI 去重与日志定位。
     */
    private fun collectTracks(mp: MediaPlayer?): List<PlayerTrack> {
        if (mp == null) return emptyList()
        return runCatching {
            val selectedVideo = mp.getVideoTrack()
            val selectedAudio = mp.getAudioTrack()
            val selectedSpu = mp.getSpuTrack()
            val result = mutableListOf<PlayerTrack>()
            appendTracks(mp.getVideoTracks(), TrackKind.VIDEO, selectedVideo, "视频轨", result)
            appendTracks(mp.getAudioTracks(), TrackKind.AUDIO, selectedAudio, "音轨", result)
            appendTracks(mp.getSpuTracks(), TrackKind.TEXT, selectedSpu, "字幕", result)
            result
        }.getOrElse {
            Log.w(TAG, "读取 LibVLC 轨道失败", it)
            emptyList()
        }
    }

    private fun appendTracks(
        descriptions: Array<out MediaPlayer.TrackDescription>?,
        kind: TrackKind,
        selectedId: Int,
        labelPrefix: String,
        out: MutableList<PlayerTrack>,
    ) {
        descriptions?.forEachIndexed { index, description ->
            val trackId = description.id
            // LibVLC 的轨道描述里包含 id=-1 的 “Disable” 伪轨道，不暴露给 UI。
            if (trackId < 0) return@forEachIndexed
            val name = description.name?.takeIf { it.isNotBlank() }
            out += PlayerTrack(
                id = "${kind.name}:0:$trackId",
                kind = kind,
                label = name ?: "$labelPrefix ${index + 1}",
                language = null,
                selected = trackId == selectedId,
                groupIndex = 0,
                trackIndex = trackId,
            )
        }
    }

    // ---------------------------------------------------------------- MediaSession / 通知

    @Suppress("DEPRECATION")
    private fun createMediaSession() {
        val session = MediaSessionCompat(this, SESSION_TAG)
        session.setCallback(sessionCallback)
        session.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )
        session.setSessionActivity(contentPendingIntent())
        session.setActive(true)
        mediaSession = session
    }

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = play()
        override fun onPause() = pause()
        override fun onSkipToNext() = playNext()
        override fun onSkipToPrevious() = playPrevious()
        override fun onSeekTo(pos: Long) = seekTo(pos)
        override fun onStop() = stopPlayback()
    }

    private fun syncMediaSession(updateMetadata: Boolean) {
        val session = mediaSession ?: return
        val mp = mediaPlayer
        val sessionState = when {
            currentError != null -> PlaybackStateCompat.STATE_ERROR
            queue.isEmpty() -> PlaybackStateCompat.STATE_NONE
            ended -> PlaybackStateCompat.STATE_STOPPED
            buffering -> PlaybackStateCompat.STATE_BUFFERING
            playWhenReady && mp?.isPlaying() == true -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val position = (mp?.getTime() ?: _state.value.positionMs).coerceAtLeast(0L)
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(MEDIA_SESSION_ACTIONS)
            .setState(sessionState, position, speed, SystemClock.elapsedRealtime())
            .build()
        runCatching { session.setPlaybackState(playbackState) }
        if (updateMetadata) {
            val item = queue.getOrNull(currentIndex)
            val duration = mp?.getLength()?.takeIf { it > 0L } ?: item?.durationMs ?: 0L
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, item?.title.orEmpty())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .build()
            runCatching { session.setMetadata(metadata) }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VLC 播放",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "LibVLC 兼容播放控制"
            setShowBadge(false)
            setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)
        }
        manager.createNotificationChannel(channel)
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        val notification = buildNotification()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                foregroundServiceType(),
            )
            foregroundStarted = true
        } catch (t: Throwable) {
            Log.e(TAG, "进入前台服务失败", t)
            currentError = "无法启动后台播放通知：${describeThrowable(t)}"
            _state.value = buildState()
        }
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }

    private fun refreshNotification() {
        if (!foregroundStarted) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification()) }
            .onFailure { Log.w(TAG, "刷新播放通知失败", it) }
    }

    private fun buildNotification(): Notification {
        val item = queue.getOrNull(currentIndex)
        val title = item?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
        val playingNow = playWhenReady && mediaPlayer?.isPlaying() == true

        val style = MediaStyle().setShowActionsInCompactView(0, 1, 2)
        mediaSession?.sessionToken?.let { token -> style.setMediaSession(token) }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_flowplayer)
            .setContentTitle(title)
            .setContentText(notificationStatusText())
            .setContentIntent(contentPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_media_previous,
                    "上一首",
                    serviceActionPendingIntent(ACTION_PREVIOUS, REQUEST_PREVIOUS),
                ),
            )
            .addAction(
                NotificationCompat.Action(
                    if (playingNow) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                    if (playingNow) "暂停" else "播放",
                    serviceActionPendingIntent(ACTION_TOGGLE_PLAY_PAUSE, REQUEST_TOGGLE),
                ),
            )
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_media_next,
                    "下一首",
                    serviceActionPendingIntent(ACTION_NEXT, REQUEST_NEXT),
                ),
            )
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "停止",
                    serviceActionPendingIntent(ACTION_STOP, REQUEST_STOP),
                ),
            )
            .setStyle(style)
            .build()
    }

    private fun notificationStatusText(): String {
        val status = currentError ?: when {
            queue.isEmpty() -> "等待播放"
            ended -> "播放结束"
            buffering -> "正在缓冲"
            playWhenReady && mediaPlayer?.isPlaying() == true -> "正在播放"
            else -> "已暂停"
        }
        val queueInfo = if (queue.size > 1) "第 ${currentIndex + 1}/${queue.size} 项" else null
        return listOfNotNull(status, queueInfo).joinToString(" · ")
    }

    private fun contentPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, VlcPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun removeForegroundAndStop() {
        if (foregroundStarted) {
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            foregroundStarted = false
        }
        stopSelf()
    }

    // ---------------------------------------------------------------- 音频焦点 / 耳机拔出

    private fun registerNoisyReceiver() {
        if (noisyRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        runCatching {
            ContextCompat.registerReceiver(
                this,
                noisyReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess {
            noisyRegistered = true
        }.onFailure {
            Log.w(TAG, "注册耳机拔出监听失败", it)
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.d(TAG, "检测到耳机拔出，暂停播放")
                pause()
            }
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            // 永久或暂时丢失焦点都暂停；兼容层不做自动恢复，避免打断其他应用。
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "音频焦点丢失：$change，暂停播放")
                pause()
            }
            // 允许闪避的场景保持播放（LibVLC 兼容层暂不实现自动降音量）。
            else -> Unit
        }
    }

    @Suppress("DEPRECATION")
    private fun requestAudioFocus(): Boolean {
        if (audioFocusGranted) return true
        val manager = audioManager ?: return false
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioFocusRequest = request
            manager.requestAudioFocus(request)
        } else {
            manager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        audioFocusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return audioFocusGranted
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        if (!audioFocusGranted) return
        val manager = audioManager
        if (manager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
            } else {
                manager.abandonAudioFocus(audioFocusListener)
            }
        }
        audioFocusRequest = null
        audioFocusGranted = false
    }

    // ---------------------------------------------------------------- 唤醒锁

    /**
     * LibVLC 不会为后台播放自动持有唤醒锁，播放期间必须手动持有 PARTIAL_WAKE_LOCK。
     * 写法与传输服务一致：引用计数关闭、失败只记录日志，不影响播放。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val manager = getSystemService(PowerManager::class.java)
            val lock = manager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$LOCK_TAG:vlc")
            if (lock != null) {
                lock.setReferenceCounted(false)
                lock.acquire()
                wakeLock = lock
            }
        } catch (t: Throwable) {
            Log.w(TAG, "获取播放 WakeLock 失败", t)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        wakeLock = null
        if (lock != null) {
            try {
                if (lock.isHeld) lock.release()
            } catch (t: Throwable) {
                Log.w(TAG, "释放播放 WakeLock 失败", t)
            }
        }
    }

    // ---------------------------------------------------------------- 定时进度

    private fun scheduleTicker() {
        if (tickerScheduled) return
        tickerScheduled = true
        mainHandler.postDelayed(tickerRunnable, TICK_INTERVAL_MS)
    }

    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (!playWhenReady || mediaPlayer == null) {
                tickerScheduled = false
                return
            }
            publishProgress()
            syncMediaSession(updateMetadata = false)
            mainHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun describeThrowable(t: Throwable): String = when (t) {
        is SecurityException -> "没有文件读取权限"
        is FileNotFoundException -> "文件不存在或无法访问"
        is IllegalArgumentException -> "文件地址无效"
        is UnsatisfiedLinkError -> "LibVLC 组件加载失败"
        is OutOfMemoryError -> "内存不足"
        else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
    }

    /**
     * 本地绑定对象。控制器拿到 service 后直接调用其公开方法，状态通过 [state] 镜像。
     */
    inner class LocalBinder : Binder() {
        val service: VlcPlaybackService
            get() = this@VlcPlaybackService
    }

    private companion object {
        const val TAG = "VlcPlaybackService"

        const val CHANNEL_ID = "flowplayer_vlc_playback"
        const val NOTIFICATION_ID = 1002
        const val SESSION_TAG = "FlowPlayerVlc"
        const val LOCK_TAG = "FlowPlayer:vlc"

        const val ACTION_PLAY = "com.bingbaihanji.flowplayer.vlc.action.PLAY"
        const val ACTION_PAUSE = "com.bingbaihanji.flowplayer.vlc.action.PAUSE"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.bingbaihanji.flowplayer.vlc.action.TOGGLE"
        const val ACTION_PREVIOUS = "com.bingbaihanji.flowplayer.vlc.action.PREVIOUS"
        const val ACTION_NEXT = "com.bingbaihanji.flowplayer.vlc.action.NEXT"
        const val ACTION_STOP = "com.bingbaihanji.flowplayer.vlc.action.STOP"

        const val REQUEST_CONTENT = 0
        const val REQUEST_PREVIOUS = 1
        const val REQUEST_TOGGLE = 2
        const val REQUEST_NEXT = 3
        const val REQUEST_STOP = 4

        const val TICK_INTERVAL_MS = 500L
        const val MIN_SPEED = 0.1f
        const val MAX_SPEED = 4.0f

        val MEDIA_SESSION_ACTIONS: Long =
            PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP
    }
}
