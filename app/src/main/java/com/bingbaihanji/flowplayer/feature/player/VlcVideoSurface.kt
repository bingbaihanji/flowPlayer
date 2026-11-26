package com.bingbaihanji.flowplayer.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.bingbaihanji.flowplayer.core.player.AspectMode
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * LibVLC 视频渲染层。
 *
 * 只负责 [VLCVideoLayout] 的创建与挂载/卸载，以及画面比例；顶部/底部控件、手势、
 * 菜单与错误遮罩完全复用 [PlayerScreen]。
 */
@Composable
internal fun VlcVideoSurface(
    viewModel: VlcPlayerViewModel,
    aspectMode: AspectMode,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val videoLayout = remember(context) {
        VLCVideoLayout(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }
    val ratio = when (aspectMode) {
        AspectMode.RATIO_16_9 -> 16f / 9f
        AspectMode.RATIO_4_3 -> 4f / 3f
        else -> null
    }

    // VLC 需要在播放开始前拿到视频布局；离开播放页时立刻解除，避免 Surface 泄漏。
    DisposableEffect(viewModel, videoLayout) {
        viewModel.attachVideo(videoLayout)
        onDispose { viewModel.detachVideo() }
    }

    LaunchedEffect(viewModel, aspectMode) {
        viewModel.setAspectMode(aspectMode)
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { videoLayout },
            modifier = if (ratio != null) {
                Modifier.aspectRatio(ratio)
            } else {
                Modifier.fillMaxSize()
            },
        )
    }
}
