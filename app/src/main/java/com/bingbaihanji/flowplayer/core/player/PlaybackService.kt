package com.bingbaihanji.flowplayer.core.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.bingbaihanji.flowplayer.MainActivity
import com.bingbaihanji.flowplayer.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 后台播放服务。
 *
 * 持有唯一的 ExoPlayer 与 MediaSession；UI 侧只能通过 MediaController 连接控制。
 * 音频焦点、耳机拔出与局部唤醒锁均由 ExoPlayer 自身处理。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(PlaybackSessionCallback())
            .build()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelName(R.string.app_name)
            .build()
        notificationProvider.setSmallIcon(R.drawable.ic_stat_flowplayer)
        setMediaNotificationProvider(notificationProvider)
        // 停止或出错后不保留空通知；暂停/播放中的通知不受影响
        setShowNotificationForIdlePlayer(MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * 用户从最近任务划掉应用时：正在播放则继续后台播放，否则暂停并结束服务。
     *
     * 未播放时必须终止服务，否则系统可能以“后台服务未停止”为由重启并崩溃。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!::player.isInitialized) {
            stopSelf()
            return
        }
        val state = player.playbackState
        if (!player.playWhenReady || state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        if (::player.isInitialized) {
            player.release()
        }
        super.onDestroy()
    }

    /**
     * 把外挂字幕追加到当前媒体项，并保留队列、进度与播放意图。
     *
     * 仅 replaceMediaItem 不会重建底层 MediaSource，新的字幕配置不会生效；
     * 因此这里重建整个队列的 MediaItem 列表，用 setMediaItems 重新设置当前索引与位置后 prepare。
     */
    private fun applyExternalSubtitle(uri: Uri, mimeType: String?): Boolean {
        if (!::player.isInitialized) return false
        val index = player.currentMediaItemIndex
        if (index == C.INDEX_UNSET || index !in 0 until player.mediaItemCount) return false
        val currentItem = player.getMediaItemAt(index)

        val resolvedMime = mimeType?.takeIf { it.isNotBlank() }
            ?: PlayerContract.subtitleMimeFor(uri.lastPathSegment.orEmpty())
            ?: return false

        val existingSubtitles = currentItem.localConfiguration?.subtitleConfigurations.orEmpty()
        // 同一个字幕地址重复导入时直接视为成功
        if (existingSubtitles.any { it.uri == uri }) return true

        val position = player.currentPosition
        val playWhenReady = player.playWhenReady

        val newSubtitle = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(resolvedMime)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        // 只替换当前项的 subtitleConfigurations，其余队列项原样保留
        val updatedItems = (0 until player.mediaItemCount).map { itemIndex ->
            val item = player.getMediaItemAt(itemIndex)
            if (itemIndex == index) {
                item.buildUpon()
                    .setSubtitleConfigurations(existingSubtitles + newSubtitle)
                    .build()
            } else {
                item
            }
        }

        player.setMediaItems(updatedItems, index, position)
        // 打开文本轨并清除旧的覆盖选择，默认标记会优先选中新字幕
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        player.prepare()
        // 恢复导入前的播放意图：播放中继续播放，暂停状态保持暂停
        player.playWhenReady = playWhenReady
        return true
    }

    private inner class PlaybackSessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val availableSessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(SessionCommand(PlayerSessionCommand.ACTION_SET_EXTERNAL_SUBTITLE, Bundle.EMPTY))
                .add(SessionCommand(PlayerSessionCommand.ACTION_SET_PAUSE_AT_END, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(availableSessionCommands)
                .build()
        }

        /** 控制器传入的 MediaItem 已带有 URI，直接交给底层播放器加载。 */
        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(mediaItems)

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                PlayerSessionCommand.ACTION_SET_EXTERNAL_SUBTITLE -> {
                    val uriString = args.getString(PlayerSessionCommand.KEY_SUBTITLE_URI)
                    val mimeType = args.getString(PlayerSessionCommand.KEY_SUBTITLE_MIME)
                    val success = if (uriString.isNullOrBlank()) {
                        false
                    } else {
                        runCatching { applyExternalSubtitle(Uri.parse(uriString), mimeType) }
                            .getOrDefault(false)
                    }
                    val resultCode = if (success) {
                        SessionResult.RESULT_SUCCESS
                    } else {
                        SessionResult.RESULT_ERROR_UNKNOWN
                    }
                    Futures.immediateFuture(SessionResult(resultCode))
                }

                PlayerSessionCommand.ACTION_SET_PAUSE_AT_END -> {
                    if (!::player.isInitialized) {
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
                    } else {
                        player.setPauseAtEndOfMediaItems(
                            args.getBoolean(PlayerSessionCommand.KEY_PAUSE_AT_END),
                        )
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }

                else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
        }
    }

    private companion object {
        const val CHANNEL_ID = "flowplayer_playback"
        const val NOTIFICATION_ID = 1001
    }
}
