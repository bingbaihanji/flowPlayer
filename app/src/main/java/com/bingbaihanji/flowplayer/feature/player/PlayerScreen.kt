package com.bingbaihanji.flowplayer.feature.player

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bingbaihanji.flowplayer.core.data.settings.SpeedMode
import com.bingbaihanji.flowplayer.core.player.AspectMode
import com.bingbaihanji.flowplayer.core.player.PlayerPlaybackState
import com.bingbaihanji.flowplayer.core.ui.theme.EvDarkOnSurfaceVariant
import com.bingbaihanji.flowplayer.core.ui.theme.EvDarkOutline
import com.bingbaihanji.flowplayer.core.ui.theme.EvPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 播放页独立深色主题：黑底、白图标、EvPrimary 主色，不修改系统栏颜色。 */
@Composable
internal fun PlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = EvPrimary,
            onPrimary = Color.White,
            background = Color.Black,
            onBackground = Color.White,
            surface = Color(0xFF141414),
            onSurface = Color.White,
            onSurfaceVariant = EvDarkOnSurfaceVariant,
            outline = EvDarkOutline,
        ),
        content = content,
    )
}

/**
 * 全屏播放页面（Media3 / LibVLC 共用）。
 *
 * 布局顺序（从底到顶）：视频 Surface → 手势层（内含手势 HUD）→ 顶部/右侧/底部控件 →
 * 错误遮罩 → 提示 Snackbar → 菜单 Sheet。
 *
 * 具体引擎由 [videoSurface] 注入；本层只依赖 [PlayerUiSession]，不感知 Media3/LibVLC。
 */
@Composable
fun PlayerScreen(
    session: PlayerUiSession,
    videoSurface: @Composable (AspectMode) -> Unit,
    isInPip: Boolean,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onEnterPip: () -> Unit,
    onImportSubtitle: () -> Unit,
    onPipConfigChanged: (Boolean, Boolean, Boolean, Int, Int) -> Unit,
    onCompatibilityFallback: (() -> Unit)? = null,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val locked by session.isLocked.collectAsStateWithLifecycle()
    val timerRemainingMs by session.timerRemainingMs.collectAsStateWithLifecycle()
    val preferredSpeedMode by session.preferredSpeedMode.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(session) {
        session.messages.collect { message ->
            launch { snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short) }
        }
    }

    // 同步 PiP 参数（宽高比、Android 12+ 自动进入）。
    LaunchedEffect(state.hasVideo, state.isPlaying, locked, state.videoWidth, state.videoHeight) {
        onPipConfigChanged(
            state.hasVideo,
            state.isPlaying,
            locked,
            state.videoWidth,
            state.videoHeight,
        )
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var activeSheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var moreExpanded by remember { mutableStateOf(false) }
    var speedListMode by remember { mutableStateOf(preferredSpeedMode.toSpeedListMode()) }
    var seekPreviewMs by remember { mutableStateOf<Long?>(null) }
    var aspectMode by remember { mutableStateOf(AspectMode.FIT) }

    // 设置读取完成或设置页修改后同步档位模式；用户本次手动切换只在会话内生效。
    LaunchedEffect(preferredSpeedMode) {
        speedListMode = preferredSpeedMode.toSpeedListMode()
    }

    fun revealControls() {
        controlsVisible = true
        interactionTick++
    }

    LaunchedEffect(isInPip) {
        if (isInPip) {
            controlsVisible = false
            activeSheet = null
            moreExpanded = false
            seekPreviewMs = null
        } else {
            revealControls()
        }
    }
    LaunchedEffect(locked) {
        if (locked) {
            controlsVisible = false
            activeSheet = null
            moreExpanded = false
        } else {
            revealControls()
        }
    }
    LaunchedEffect(state.currentIndex) {
        seekPreviewMs = null
    }
    // 从播放切到暂停（含通知栏暂停）时让控件常显。
    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying && !isInPip && !locked) {
            revealControls()
        }
    }
    // 控件 5 秒自动隐藏；暂停、锁定、PiP、菜单打开时保持显示。
    LaunchedEffect(
        controlsVisible,
        state.isPlaying,
        locked,
        activeSheet,
        moreExpanded,
        isInPip,
        interactionTick,
    ) {
        if (!isInPip && !locked && controlsVisible && state.isPlaying &&
            activeSheet == null && !moreExpanded
        ) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        videoSurface(aspectMode)

        if (!isInPip) {
            PlayerGestureSurface(
                state = state,
                locked = locked,
                enabled = state.connected && state.errorMessage == null,
                onTogglePlayPause = {
                    session.togglePlayPause()
                    revealControls()
                },
                onToggleControls = {
                    if (controlsVisible) {
                        controlsVisible = false
                    } else {
                        revealControls()
                    }
                },
                onSeekCommit = { target ->
                    session.seekTo(target)
                    revealControls()
                },
                onBoostStart = { session.startBoost() },
                onBoostEnd = { session.endBoost() },
                modifier = Modifier.fillMaxSize(),
            )

            if (state.errorMessage == null && isAudioOnly(state)) {
                AudioPlaceholder(
                    title = state.title.ifBlank { session.initialTitle },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            if (state.errorMessage == null && (!state.connected || state.isBuffering)) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp),
                    color = Color.White,
                    strokeWidth = 3.dp,
                )
            }

            when {
                locked -> PlayerLockOverlay(
                    onUnlock = {
                        session.setLocked(false)
                        revealControls()
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                controlsVisible -> {
                    PlayerTopBar(
                        title = state.title.ifBlank { session.initialTitle },
                        onBack = onBack,
                        moreExpanded = moreExpanded,
                        onMoreClick = {
                            moreExpanded = !moreExpanded
                            revealControls()
                        },
                        onDismissMore = { moreExpanded = false },
                        onOpenSheet = { sheet ->
                            moreExpanded = false
                            activeSheet = sheet
                            revealControls()
                        },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    PlayerSideControls(
                        showPip = state.hasVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
                        onToggleLock = {
                            session.setLocked(true)
                            controlsVisible = false
                        },
                        onEnterPip = onEnterPip,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                    PlayerBottomBar(
                        state = state,
                        seekPreviewMs = seekPreviewMs,
                        speedText = speedLabel(state.speed),
                        isLandscape = isLandscape,
                        onSeekPreview = { target ->
                            seekPreviewMs = target
                            revealControls()
                        },
                        onSeekCommit = { target ->
                            seekPreviewMs = null
                            session.seekTo(target)
                            revealControls()
                        },
                        onPrevious = {
                            session.playPrevious()
                            revealControls()
                        },
                        onPlayPause = {
                            session.togglePlayPause()
                            revealControls()
                        },
                        onNext = {
                            session.playNext()
                            revealControls()
                        },
                        onSpeedClick = {
                            activeSheet = PlayerSheet.SPEED
                            revealControls()
                        },
                        onQueueClick = {
                            activeSheet = PlayerSheet.QUEUE
                            revealControls()
                        },
                        onFullscreenClick = {
                            onToggleFullscreen()
                            revealControls()
                        },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            if (state.errorMessage != null) {
                PlayerErrorOverlay(
                    message = state.errorMessage.orEmpty(),
                    onRetry = { session.retry() },
                    onOpenWithOtherApp = { openWithOtherApp(context, state) },
                    onCompatibilityFallback = onCompatibilityFallback,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            val sheet = activeSheet
            if (sheet != null) {
                PlayerMenuSheet(
                    sheet = sheet,
                    state = state,
                    timerRemainingMs = timerRemainingMs,
                    queueTitle = session.queueTitle,
                    queue = session.queue,
                    aspectMode = aspectMode,
                    speedListMode = speedListMode,
                    onSpeedListModeChange = { speedListMode = it },
                    onDismiss = { activeSheet = null },
                    onSpeed = { session.setSpeed(it) },
                    onPlayMode = { session.setPlayMode(it) },
                    onDisableSubtitles = { session.disableSubtitles() },
                    onSelectTrack = { session.selectTrack(it) },
                    onImportSubtitle = onImportSubtitle,
                    onAspectMode = { aspectMode = it },
                    onStartSleepTimer = { session.startSleepTimer(it) },
                    onCancelSleepTimer = { session.cancelSleepTimer() },
                    onSelectQueueIndex = { index ->
                        session.selectQueueItem(index)
                        activeSheet = null
                    },
                )
            }
        }

        if (!isInPip) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (controlsVisible && !locked) 132.dp else 16.dp),
            )
        }
    }
}

@Composable
private fun AudioPlaceholder(title: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PlayerErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onOpenWithOtherApp: () -> Unit,
    onCompatibilityFallback: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "播放失败",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onRetry) {
                    Text(text = "重试", color = EvPrimary)
                }
                TextButton(onClick = onOpenWithOtherApp) {
                    Text(text = "用其他应用打开", color = Color.White)
                }
            }
            // Media3 失败时提供 LibVLC 兼容模式；VLC 会话不传回调，按钮不显示。
            if (onCompatibilityFallback != null) {
                TextButton(onClick = onCompatibilityFallback) {
                    Text(text = "兼容模式播放", color = EvPrimary)
                }
            }
        }
    }
}

/** 设置页的倍速模式映射到播放页倍速菜单的档位模式。 */
private fun SpeedMode.toSpeedListMode(): SpeedListMode = when (this) {
    SpeedMode.NORMAL -> SpeedListMode.NORMAL
    SpeedMode.FINE -> SpeedListMode.FINE
}

private fun isAudioOnly(state: PlayerPlaybackState): Boolean {
    if (state.mimeType.startsWith("audio", ignoreCase = true)) return true
    val extension = state.path.substringAfterLast('.', "").lowercase()
    return extension in AUDIO_EXTENSIONS
}

private fun openWithOtherApp(context: Context, state: PlayerPlaybackState) {
    if (state.uri.isBlank()) {
        Toast.makeText(context, "没有可用的文件地址", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = runCatching { Uri.parse(state.uri) }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, state.mimeType.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, "用其他应用打开")
    if (context is Activity) {
        chooser.putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS,
            arrayOf<Parcelable>(ComponentName(context, PlayerActivity::class.java)),
        )
    } else {
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "没有可用的播放应用", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "没有权限打开该文件", Toast.LENGTH_SHORT).show()
    } catch (_: android.os.FileUriExposedException) {
        Toast.makeText(context, "本地文件地址无法共享给其他应用", Toast.LENGTH_SHORT).show()
    }
}

private const val CONTROLS_AUTO_HIDE_MS = 5_000L
private val AUDIO_EXTENSIONS = setOf(
    "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr", "ape", "mid", "midi",
)
