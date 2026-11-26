package com.bingbaihanji.flowplayer.feature.player

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import com.bingbaihanji.flowplayer.core.player.PlaybackEngine
import com.bingbaihanji.flowplayer.core.player.PlaybackRequest
import com.bingbaihanji.flowplayer.core.player.PlayerContract
import com.bingbaihanji.flowplayer.core.player.PlayerLauncher

/**
 * LibVLC 兼容播放页。
 *
 * 只接受 [PlayerContract.read] 的播放请求（App 内启动或 Media3 兼容模式切换）；系统 VIEW
 * 由 [PlayerActivity] 负责转发。页面结构与 [PlayerActivity] 一致：黑色 edge-to-edge、
 * 全屏旋转、PiP、通知权限、onStop 保存历史、返回停止播放。
 */
class VlcPlayerActivity : ComponentActivity() {

    private lateinit var playbackRequest: PlaybackRequest

    private val viewModel: VlcPlayerViewModel by viewModels {
        VlcPlayerViewModel.factory(application, playbackRequest)
    }

    /** Compose 读取的 PiP 状态；进入/退出画中画时更新。 */
    val pipModeState = mutableStateOf(false)

    private var lastPipParams: PictureInPictureParams? = null

    /** 打开系统文件选择器等外部界面时临时关闭自动 PiP，避免选字幕时误入画中画。 */
    private var suppressPipForExternalUi = false
    private var lastPipHasVideo = false
    private var lastPipIsPlaying = false
    private var lastPipLocked = false
    private var lastPipVideoWidth = 0
    private var lastPipVideoHeight = 0

    private val subtitlePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importSubtitle(uri)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝不阻塞播放 */ }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val request = PlayerContract.read(intent)
        if (request == null || request.items.isEmpty()) {
            finish()
            return
        }
        playbackRequest = request

        enableEdgeToEdge()
        window.setBackgroundDrawable(Color.BLACK.toDrawable())
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // 默认竖屏（含反向竖屏）；全屏按钮切换到 SENSOR_LANDSCAPE，再点回到 UNSPECIFIED。
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

        onBackPressedDispatcher.addCallback(this) {
            if (viewModel.isLocked.value) {
                viewModel.setLocked(false)
            } else {
                viewModel.stopPlayback()
                finish()
            }
        }

        requestNotificationPermissionIfNeeded()

        setContent {
            PlayerTheme {
                PlayerScreen(
                    session = viewModel,
                    videoSurface = { aspectMode ->
                        VlcVideoSurface(
                            viewModel = viewModel,
                            aspectMode = aspectMode,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    isInPip = pipModeState.value,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onToggleFullscreen = { toggleFullscreen() },
                    onEnterPip = { enterPipFromUi() },
                    onImportSubtitle = { launchSubtitlePicker() },
                    onPipConfigChanged = { hasVideo, isPlaying, locked, width, height ->
                        updatePipParams(hasVideo, isPlaying, locked, width, height)
                    },
                    onCompatibilityFallback = null,
                )
            }
        }
    }

    /**
     * singleTop 模式下收到新的播放请求：先保存并停止旧播放，再以 VLC 引擎重启本页。
     * 兼容页固定走 VLC，避免与 Media3 页因引擎选择来回切换形成循环。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val request = PlayerContract.read(intent)
        if (request == null || request.items.isEmpty()) return
        if (this::playbackRequest.isInitialized) {
            viewModel.stopPlayback()
        }
        // 先 finish 再启动，避免目标仍是当前 singleTop 实例时反复触发 onNewIntent。
        finish()
        PlayerLauncher.start(this, request, PlaybackEngine.VLC)
    }

    override fun onResume() {
        super.onResume()
        if (suppressPipForExternalUi) {
            suppressPipForExternalUi = false
            refreshPipParams()
        }
    }

    override fun onStop() {
        // stopPlayback 已记录停止前进度；ViewModel 内部会忽略停止后的再次写入，避免 0 覆盖历史。
        if (this::playbackRequest.isInitialized) {
            viewModel.saveHistoryNow()
        }
        super.onStop()
    }

    // ---------------------------------------------------------------- 全屏

    fun toggleFullscreen() {
        requestedOrientation =
            if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
    }

    // ---------------------------------------------------------------- PiP

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!this::playbackRequest.isInitialized) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (pipModeState.value || suppressPipForExternalUi) return
        val state = viewModel.state.value
        if (state.hasVideo && state.isPlaying && !viewModel.isLocked.value) {
            enterPipInternal()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        }
        pipModeState.value = isInPictureInPictureMode
    }

    /** Compose 状态变化时同步 PiP 参数；Android 12+ 打开自动进入画中画。 */
    fun updatePipParams(
        hasVideo: Boolean,
        isPlaying: Boolean,
        locked: Boolean,
        videoWidth: Int,
        videoHeight: Int,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        lastPipHasVideo = hasVideo
        lastPipIsPlaying = isPlaying
        lastPipLocked = locked
        lastPipVideoWidth = videoWidth
        lastPipVideoHeight = videoHeight
        val builder = PictureInPictureParams.Builder()
        if (hasVideo) {
            builder.setAspectRatio(pipAspectRatio(videoWidth, videoHeight))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(
                hasVideo && isPlaying && !locked && !suppressPipForExternalUi,
            )
        }
        val params = builder.build()
        lastPipParams = params
        runCatching { setPictureInPictureParams(params) }
    }

    /** 打开字幕文件选择器；期间关闭自动 PiP。 */
    private fun launchSubtitlePicker() {
        suppressPipForExternalUi = true
        refreshPipParams()
        subtitlePicker.launch(arrayOf("*/*"))
    }

    private fun refreshPipParams() {
        updatePipParams(
            hasVideo = lastPipHasVideo,
            isPlaying = lastPipIsPlaying,
            locked = lastPipLocked,
            videoWidth = lastPipVideoWidth,
            videoHeight = lastPipVideoHeight,
        )
    }

    /** 右侧 PiP 按钮：有视频时手动进入画中画。 */
    fun enterPipFromUi() {
        if (!this::playbackRequest.isInitialized) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!viewModel.state.value.hasVideo) return
        enterPipInternal()
    }

    private fun enterPipInternal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val params = lastPipParams ?: PictureInPictureParams.Builder().build()
        runCatching { enterPictureInPictureMode(params) }
    }

    private fun pipAspectRatio(width: Int, height: Int): Rational {
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val ratio = width.toFloat() / height.toFloat()
        return when {
            ratio > MAX_PIP_RATIO -> Rational(239, 100)
            ratio < MIN_PIP_RATIO -> Rational(100, 239)
            else -> Rational(width, height)
        }
    }

    // ---------------------------------------------------------------- 通知权限

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        runCatching { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    private companion object {
        const val MAX_PIP_RATIO = 2.39f
        const val MIN_PIP_RATIO = 0.42f
    }
}
