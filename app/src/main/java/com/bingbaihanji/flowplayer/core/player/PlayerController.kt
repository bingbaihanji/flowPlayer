package com.bingbaihanji.flowplayer.core.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 供 PlayerView 渲染使用的播放器类型。
 *
 * 约定只暴露 MediaController（其本身实现 Player），绝不把 ExoPlayer 交给 UI。
 */
typealias PlaybackPlayer = Player

/**
 * 播放控制客户端（UI 与服务之间的唯一桥梁）。
 *
 * 通过 [MediaController] 连接 [PlaybackService]；连接前收到的 [playQueue] 请求会被缓存，
 * 连接成功后自动下发。所有回调与状态更新均在主线程完成。
 */
@OptIn(UnstableApi::class)
class PlayerController(context: Context) {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlayerPlaybackState())
    val state: StateFlow<PlayerPlaybackState> = _state.asStateFlow()

    private val _renderPlayer = MutableStateFlow<PlaybackPlayer?>(null)
    val renderPlayer: StateFlow<PlaybackPlayer?> = _renderPlayer.asStateFlow()

    private var controller: MediaController? = null
    private var connecting = false
    private var released = false
    private var tickerJob: Job? = null

    private var pendingRequest: PlaybackRequest? = null
    private var pendingPlayMode: PlayMode? = null
    private var pendingSpeed: Float? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refreshState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _state.update { it.copy(errorMessage = null) }
            refreshState()
        }

        override fun onTracksChanged(tracks: Tracks) {
            refreshState()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            refreshState()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _state.update { it.copy(speed = playbackParameters.speed) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                _state.update { it.copy(errorMessage = null) }
            }
            refreshState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshState()
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update {
                it.copy(
                    isBuffering = false,
                    errorMessage = "播放失败：${describeError(error)}",
                )
            }
            controller?.let { refreshProgress(it) }
        }
    }

    /**
     * 连接播放服务；幂等，release 后不再生效。
     */
    fun connect() {
        if (released || connecting || controller != null) return
        connecting = true

        val token = SessionToken(
            appContext,
            ComponentName(appContext, PlaybackService::class.java),
        )
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            connecting = false
            val newController = runCatching { future.get() }.getOrNull()
            if (newController == null) {
                if (!released) {
                    _state.update {
                        it.copy(connected = false, errorMessage = "无法连接播放服务")
                    }
                }
                return@addListener
            }
            // 已释放时把迟到的连接立即关掉
            if (released) {
                newController.release()
                return@addListener
            }

            controller = newController
            newController.addListener(playerListener)
            _renderPlayer.value = newController

            // 应用连接前缓存的用户设置
            pendingPlayMode?.let {
                pendingPlayMode = null
                applyPlayMode(newController, it)
            }
            pendingSpeed?.let {
                pendingSpeed = null
                newController.setPlaybackSpeed(it)
            }
            pendingRequest?.let {
                pendingRequest = null
                applyPlayQueue(it)
            }

            startTicker()
            refreshState()
        }, ContextCompat.getMainExecutor(appContext))
    }

    /**
     * 断开并释放控制器；播放服务与后台播放不受影响。释放后不可再次连接。
     */
    fun release() {
        if (released) return
        released = true
        tickerJob?.cancel()
        tickerJob = null
        pendingRequest = null
        pendingPlayMode = null
        pendingSpeed = null
        controller?.let {
            it.removeListener(playerListener)
            it.release()
        }
        controller = null
        _renderPlayer.value = null
        _state.update { it.copy(connected = false, isPlaying = false, isBuffering = false) }
        scope.cancel()
    }

    /**
     * 播放一个队列；未连接时缓存请求，连接成功后自动执行。
     */
    fun playQueue(request: PlaybackRequest) {
        if (request.items.isEmpty()) return
        val player = controller
        if (player == null) {
            pendingRequest = request
            connect()
            return
        }
        applyPlayQueue(request)
    }

    fun togglePlayPause() {
        val player = controller ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekToDefaultPosition()
            }
            play()
        }
    }

    fun play() {
        val player = controller ?: return
        // stop() 或错误后播放器处于 IDLE，需要重新 prepare 才能出声音
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
            player.prepare()
        }
        player.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun seekTo(positionMs: Long) {
        val player = controller ?: return
        player.seekTo(clampPosition(player, positionMs))
        refreshProgress(player)
    }

    fun seekBy(deltaMs: Long) {
        val player = controller ?: return
        seekTo(player.currentPosition + deltaMs)
    }

    fun playNext() {
        val player = controller ?: return
        if (!player.hasNextMediaItem()) return
        val wasEnded = player.playbackState == Player.STATE_ENDED
        player.seekToNextMediaItem()
        if (wasEnded) player.play()
        refreshState()
    }

    fun playPrevious() {
        val player = controller ?: return
        if (!player.hasPreviousMediaItem()) return
        val wasEnded = player.playbackState == Player.STATE_ENDED
        player.seekToPreviousMediaItem()
        if (wasEnded) player.play()
        refreshState()
    }

    fun selectQueueItem(index: Int) {
        val player = controller ?: return
        if (index !in 0 until player.mediaItemCount) return
        player.seekToDefaultPosition(index)
        player.play()
        refreshState()
    }

    fun setSpeed(speed: Float) {
        val normalized = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        _state.update { it.copy(speed = normalized) }
        val player = controller
        if (player == null) {
            pendingSpeed = normalized
        } else {
            player.setPlaybackSpeed(normalized)
        }
    }

    fun setPlayMode(mode: PlayMode) {
        _state.update { it.copy(playMode = mode) }
        val player = controller
        if (player == null) {
            pendingPlayMode = mode
        } else {
            applyPlayMode(player, mode)
        }
    }

    fun selectAudioTrack(track: PlayerTrack) {
        if (track.kind != TrackKind.AUDIO) return
        selectTrack(C.TRACK_TYPE_AUDIO, track)
    }

    fun selectVideoTrack(track: PlayerTrack) {
        if (track.kind != TrackKind.VIDEO) return
        selectTrack(C.TRACK_TYPE_VIDEO, track)
    }

    /**
     * 选择字幕轨；传 null 关闭字幕（禁用文本轨）。
     */
    fun selectTextTrack(track: PlayerTrack?) {
        val player = controller ?: return
        if (track == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            refreshState()
            return
        }
        if (track.kind != TrackKind.TEXT) return
        selectTrack(C.TRACK_TYPE_TEXT, track)
    }

    /**
     * 通过自定义命令让服务为当前媒体项添加外挂字幕。回调在主线程执行。
     */
    fun setExternalSubtitle(uri: Uri, mimeType: String?, onResult: (Result<Unit>) -> Unit = {}) {
        val player = controller
        if (player == null) {
            onResult(Result.failure(IllegalStateException("播放器未连接")))
            return
        }
        val resolvedMime = mimeType?.takeIf { it.isNotBlank() }
            ?: PlayerContract.subtitleMimeFor(uri.lastPathSegment.orEmpty())
        if (resolvedMime == null) {
            onResult(Result.failure(IllegalArgumentException("不支持的字幕格式")))
            return
        }

        val args = Bundle().apply {
            putString(PlayerSessionCommand.KEY_SUBTITLE_URI, uri.toString())
            putString(PlayerSessionCommand.KEY_SUBTITLE_MIME, resolvedMime)
        }
        val command = SessionCommand(PlayerSessionCommand.ACTION_SET_EXTERNAL_SUBTITLE, Bundle.EMPTY)
        val future = runCatching { player.sendCustomCommand(command, args) }.getOrNull()
        if (future == null) {
            onResult(Result.failure(IllegalStateException("字幕导入失败")))
            return
        }
        future.addListener({
            val result = runCatching { future.get() }.getOrNull()
            if (result != null && result.resultCode == SessionResult.RESULT_SUCCESS) {
                refreshState()
                onResult(Result.success(Unit))
            } else {
                onResult(Result.failure(IllegalStateException("字幕导入失败")))
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun retry() {
        val player = controller
        if (player == null) {
            if (pendingRequest != null) connect()
            return
        }
        _state.update { it.copy(errorMessage = null) }
        if (player.mediaItemCount > 0) {
            player.prepare()
            player.play()
        } else {
            pendingRequest?.let {
                pendingRequest = null
                applyPlayQueue(it)
            }
        }
        refreshState()
    }

    fun stop() {
        pendingRequest = null
        controller?.stop()
    }

    // ---------------------------------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------------------------------

    private fun applyPlayQueue(request: PlaybackRequest) {
        val player = controller ?: return
        player.setMediaItems(
            buildMediaItems(request.items),
            request.safeStartIndex,
            request.startPositionMs.coerceAtLeast(0L),
        )
        _state.update { it.copy(errorMessage = null) }
        player.prepare()
        player.playWhenReady = true
        refreshState()
    }

    private fun buildMediaItems(items: List<PlayerQueueItem>): List<MediaItem> = items.map { item ->
        MediaItem.Builder()
            .setUri(Uri.parse(item.uri))
            .setMediaId(item.path)
            .setMimeType(item.mimeType.takeIf { it.isNotBlank() })
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
            .build()
    }

    private fun applyPlayMode(player: Player, mode: PlayMode) {
        when (mode) {
            PlayMode.SEQUENTIAL -> {
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.shuffleModeEnabled = false
            }

            PlayMode.REPEAT_ONE -> {
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.shuffleModeEnabled = false
            }

            PlayMode.SHUFFLE -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = true
            }
        }
    }

    private fun selectTrack(trackType: Int, track: PlayerTrack) {
        val player = controller ?: return
        val group = player.currentTracks.groups
            .getOrNull(track.groupIndex)
            ?.takeIf { it.type == trackType }
            ?: return
        if (track.trackIndex !in 0 until group.length) return

        val override = TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(override)
            .build()
        refreshState()
    }

    /** 全量刷新；进度类字段除外由 ticker/seek 负责。 */
    private fun refreshState() {
        val player = controller
        if (player == null) {
            _state.update { it.copy(connected = false) }
            return
        }

        val currentItem = player.currentMediaItem
        val localConfig = currentItem?.localConfiguration
        val metadata = currentItem?.mediaMetadata
        val tracks = mapTracks(player.currentTracks)
        val textDisabled = player.trackSelectionParameters.disabledTrackTypes
            .contains(C.TRACK_TYPE_TEXT)
        val textSelected = tracks.any { it.kind == TrackKind.TEXT && it.selected }
        val mode = when {
            player.shuffleModeEnabled -> PlayMode.SHUFFLE
            player.repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.REPEAT_ONE
            else -> PlayMode.SEQUENTIAL
        }
        val videoSize = player.videoSize

        _state.update { old ->
            old.copy(
                connected = true,
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                isEnded = player.playbackState == Player.STATE_ENDED,
                durationMs = validDuration(player.duration),
                positionMs = player.currentPosition.coerceAtLeast(0L),
                bufferedMs = player.bufferedPosition
                    .takeIf { it != C.TIME_UNSET && it >= 0L } ?: 0L,
                speed = player.playbackParameters.speed,
                playMode = mode,
                currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
                itemCount = player.mediaItemCount,
                hasNext = player.hasNextMediaItem(),
                hasPrevious = player.hasPreviousMediaItem(),
                title = metadata?.title?.toString()?.takeIf { it.isNotBlank() }
                    ?: fallbackTitle(currentItem),
                uri = localConfig?.uri?.toString().orEmpty(),
                path = currentItem?.mediaId.orEmpty(),
                mimeType = localConfig?.mimeType.orEmpty(),
                videoWidth = videoSize.width,
                videoHeight = videoSize.height,
                tracks = tracks,
                subtitlesEnabled = textSelected && !textDisabled,
                errorMessage = old.errorMessage,
            )
        }
    }

    /** ticker 用：500ms 刷新一次进度。 */
    private fun refreshProgress(player: Player) {
        _state.update { old ->
            old.copy(
                durationMs = validDuration(player.duration),
                positionMs = player.currentPosition.coerceAtLeast(0L),
                bufferedMs = player.bufferedPosition
                    .takeIf { it != C.TIME_UNSET && it >= 0L } ?: 0L,
            )
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                val player = controller ?: continue
                refreshProgress(player)
            }
        }
    }

    private fun mapTracks(tracks: Tracks): List<PlayerTrack> {
        val result = mutableListOf<PlayerTrack>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            val kind = when (group.type) {
                C.TRACK_TYPE_AUDIO -> TrackKind.AUDIO
                C.TRACK_TYPE_VIDEO -> TrackKind.VIDEO
                C.TRACK_TYPE_TEXT -> TrackKind.TEXT
                else -> null
            } ?: return@forEachIndexed

            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                result += PlayerTrack(
                    id = "${kind.name}:$groupIndex:$trackIndex",
                    kind = kind,
                    label = format.label?.takeIf { it.isNotBlank() }
                        ?: format.language?.takeIf { it.isNotBlank() }
                        ?: "轨道 ${trackIndex + 1}",
                    language = format.language?.takeIf { it.isNotBlank() },
                    selected = group.isTrackSelected(trackIndex),
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                )
            }
        }
        return result
    }

    private fun clampPosition(player: Player, positionMs: Long): Long {
        val duration = player.duration
        val upper = if (duration != C.TIME_UNSET && duration > 0L) duration else Long.MAX_VALUE
        return positionMs.coerceIn(0L, upper)
    }

    private fun validDuration(durationMs: Long): Long =
        durationMs.takeIf { it != C.TIME_UNSET && it >= 0L } ?: 0L

    private fun fallbackTitle(item: MediaItem?): String {
        item ?: return ""
        val fromId = item.mediaId.substringAfterLast('/').substringAfterLast('\\')
        return fromId.ifBlank { item.localConfiguration?.uri?.lastPathSegment.orEmpty() }
    }

    private fun describeError(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件不存在或无法访问"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "没有文件读取权限"
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "不允许明文网络请求"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码器初始化失败"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "不支持的音视频格式"
        else -> error.errorCodeName.ifBlank { "未知错误" }
    }

    private companion object {
        const val TICK_INTERVAL_MS = 500L
        const val MIN_SPEED = 0.1f
        const val MAX_SPEED = 4.0f
    }
}
