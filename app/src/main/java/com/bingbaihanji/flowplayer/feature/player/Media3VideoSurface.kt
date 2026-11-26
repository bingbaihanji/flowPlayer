package com.bingbaihanji.flowplayer.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.bingbaihanji.flowplayer.core.player.AspectMode

/**
 * Media3 视频渲染层。
 *
 * 只负责 PlayerView 的创建、player 绑定与画面比例更新，不持有任何控件/手势/菜单状态。
 * LibVLC 兼容层由同包的 [VlcVideoSurface] 提供同等职责，二者共用 [PlayerScreen] 的控制层。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun Media3VideoSurface(
    player: Player?,
    aspectMode: AspectMode,
    modifier: Modifier = Modifier,
) {
    val resizeMode = when (aspectMode) {
        AspectMode.FIT, AspectMode.RATIO_16_9, AspectMode.RATIO_4_3 ->
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }
    val ratio = when (aspectMode) {
        AspectMode.RATIO_16_9 -> 16f / 9f
        AspectMode.RATIO_4_3 -> 4f / 3f
        else -> null
    }
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    setUseController(false)
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                view.setPlayer(player)
                view.setResizeMode(resizeMode)
            },
            modifier = if (ratio != null) {
                Modifier.aspectRatio(ratio)
            } else {
                Modifier.fillMaxSize()
            },
        )
    }
}
