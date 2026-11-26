package com.bingbaihanji.flowplayer.feature.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bingbaihanji.flowplayer.core.player.PlayerPlaybackState
import com.bingbaihanji.flowplayer.core.ui.theme.EvPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 播放器手势层，覆盖在视频之上、控件之下。
 *
 * 行为（以原版反编译结果为准）：
 * - 单击：显示/隐藏控件；
 * - 双击：任意区域播放/暂停；
 * - 长按（约 500ms）且播放中：3.0x 加速，松手恢复原倍速；HUD 显示“3.0X 加速中”；
 * - 横滑：按总时长比例 seek，拖动距离相对全屏宽度成平方关系（原版 f9*|f9|*duration），松手才 seek；
 * - 左边缘约 25% 区域竖滑：窗口亮度；右边缘约 25% 区域竖滑：媒体音量；中间竖滑忽略。
 *
 * 控件层的按钮会消费事件，因此不会和手势冲突；只有在控件空白区域的事件才会落到本层。
 */
@Composable
fun PlayerGestureSurface(
    state: PlayerPlaybackState,
    locked: Boolean,
    enabled: Boolean,
    onTogglePlayPause: () -> Unit,
    onToggleControls: () -> Unit,
    onSeekCommit: (Long) -> Unit,
    onBoostStart: () -> Unit,
    onBoostEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context as? Activity }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val scope = rememberCoroutineScope()

    val currentState by rememberUpdatedState(state)
    val currentLocked by rememberUpdatedState(locked)
    val currentTogglePlayPause by rememberUpdatedState(onTogglePlayPause)
    val currentToggleControls by rememberUpdatedState(onToggleControls)
    val currentSeekCommit by rememberUpdatedState(onSeekCommit)
    val currentBoostStart by rememberUpdatedState(onBoostStart)
    val currentBoostEnd by rememberUpdatedState(onBoostEnd)

    var hud by remember { mutableStateOf<GestureHud?>(null) }
    var hudToken by remember { mutableIntStateOf(0) }
    var boostActive by remember { mutableStateOf(false) }

    fun showHud(next: GestureHud) {
        hud = next
        hudToken++
    }

    // 手势 HUD 1 秒后自动消失；长按加速 HUD 在按住期间常显，松手时清除。
    LaunchedEffect(hudToken) {
        if (hudToken > 0 && hud !is GestureHud.Boost) {
            delay(HUD_AUTO_HIDE_MS)
            hud = null
        }
    }

    // 锁定或不可用时立刻收尾，避免长按加速状态残留。
    LaunchedEffect(locked, enabled) {
        if (locked || !enabled) {
            if (boostActive) {
                boostActive = false
                currentBoostEnd()
            }
            hud = null
        }
    }

    // 进入 PiP / 离开播放页时手势层被移除，恢复加速前的倍速。
    DisposableEffect(Unit) {
        onDispose {
            if (boostActive) {
                boostActive = false
                currentBoostEnd()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(locked, enabled) {
                if (locked || !enabled) return@pointerInput
                val longPressMs = viewConfiguration.longPressTimeoutMillis
                detectTapGestures(
                    onTap = { currentToggleControls() },
                    onDoubleTap = { currentTogglePlayPause() },
                    onPress = {
                        var boostStarted = false
                        val boostJob = scope.launch {
                            delay(longPressMs + 60L)
                            if (currentState.isPlaying && !currentLocked) {
                                boostStarted = true
                                boostActive = true
                                currentBoostStart()
                                showHud(GestureHud.Boost)
                            }
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            boostJob.cancel()
                            if (boostStarted) {
                                boostActive = false
                                currentBoostEnd()
                                hud = null
                                hudToken++
                            }
                        }
                    },
                )
            }
            .pointerInput(locked, enabled) {
                if (locked || !enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()
                    if (width <= 0f || height <= 0f) return@awaitEachGesture

                    var axis: GestureAxis? = null
                    var verticalZone = VerticalZone.NONE
                    var durationMs = 0L
                    var seekStartMs = 0L
                    var seekTargetMs = 0L
                    var seekForward = true
                    var brightnessStart = 0.5f
                    var volumeStartFraction = 0f
                    var volumeMax = 1
                    var lastX = down.position.x
                    var lastY = down.position.y
                    var totalX = 0f
                    var totalY = 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dx = change.position.x - lastX
                        val dy = change.position.y - lastY
                        lastX = change.position.x
                        lastY = change.position.y
                        totalX += dx
                        totalY += dy

                        if (axis == null) {
                            val touchSlop = viewConfiguration.touchSlop
                            if (abs(totalX) > touchSlop || abs(totalY) > touchSlop) {
                                axis = if (abs(totalX) >= abs(totalY)) {
                                    GestureAxis.HORIZONTAL
                                } else {
                                    GestureAxis.VERTICAL
                                }
                                if (axis == GestureAxis.HORIZONTAL) {
                                    durationMs = currentState.durationMs
                                    seekStartMs = currentState.positionMs
                                        .coerceIn(0L, durationMs.coerceAtLeast(0L))
                                } else {
                                    verticalZone = when {
                                        down.position.x < width * BRIGHTNESS_ZONE_FRACTION ->
                                            VerticalZone.BRIGHTNESS
                                        down.position.x > width * VOLUME_ZONE_START ->
                                            VerticalZone.VOLUME
                                        else -> VerticalZone.NONE
                                    }
                                    if (verticalZone == VerticalZone.BRIGHTNESS) {
                                        brightnessStart = currentWindowBrightness(activity)
                                    }
                                    if (verticalZone == VerticalZone.VOLUME && audioManager != null) {
                                        volumeMax = audioManager
                                            .getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                            .coerceAtLeast(1)
                                        volumeStartFraction =
                                            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                                .toFloat() / volumeMax
                                    }
                                }
                                change.consume()
                            }
                        }

                        when (axis) {
                            GestureAxis.HORIZONTAL -> {
                                if (durationMs > 0L) {
                                    val fraction = totalX / width
                                    val rawDelta = fraction * abs(fraction) * durationMs.toFloat()
                                    val delta = rawDelta.coerceIn(
                                        -durationMs.toFloat(),
                                        durationMs.toFloat(),
                                    )
                                    seekTargetMs = (seekStartMs + delta.toLong())
                                        .coerceIn(0L, durationMs)
                                    seekForward = delta >= 0f
                                    showHud(
                                        GestureHud.Seek(
                                            targetMs = seekTargetMs,
                                            forward = seekForward,
                                        ),
                                    )
                                }
                                change.consume()
                            }

                            GestureAxis.VERTICAL -> {
                                when (verticalZone) {
                                    VerticalZone.BRIGHTNESS -> {
                                        val fraction = (brightnessStart - totalY / height)
                                            .coerceIn(MIN_BRIGHTNESS, 1f)
                                        applyWindowBrightness(activity, fraction)
                                        showHud(
                                            GestureHud.Level(
                                                kind = GestureLevelKind.BRIGHTNESS,
                                                fraction = fraction,
                                            ),
                                        )
                                        change.consume()
                                    }

                                    VerticalZone.VOLUME -> {
                                        if (audioManager != null) {
                                            val fraction = (volumeStartFraction - totalY / height)
                                                .coerceIn(0f, 1f)
                                            val index = (fraction * volumeMax)
                                                .roundToInt()
                                                .coerceIn(0, volumeMax)
                                            audioManager.setStreamVolume(
                                                AudioManager.STREAM_MUSIC,
                                                index,
                                                0,
                                            )
                                            showHud(
                                                GestureHud.Level(
                                                    kind = GestureLevelKind.VOLUME,
                                                    fraction = fraction,
                                                ),
                                            )
                                        }
                                        change.consume()
                                    }

                                    VerticalZone.NONE -> change.consume()
                                }
                            }

                            null -> Unit
                        }
                    }

                    if (axis == GestureAxis.HORIZONTAL && durationMs > 0L) {
                        currentSeekCommit(seekTargetMs)
                        showHud(
                            GestureHud.Seek(
                                targetMs = seekTargetMs,
                                forward = seekForward,
                            ),
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val currentHud = hud
        if (currentHud != null) {
            GestureHudView(currentHud)
        }
    }
}

// ---------------------------------------------------------------- 手势 HUD

private sealed interface GestureHud {
    data class Seek(val targetMs: Long, val forward: Boolean) : GestureHud
    data class Level(val kind: GestureLevelKind, val fraction: Float) : GestureHud
    data object Boost : GestureHud
}

private enum class GestureLevelKind { BRIGHTNESS, VOLUME }

private enum class GestureAxis { HORIZONTAL, VERTICAL }

private enum class VerticalZone { NONE, BRIGHTNESS, VOLUME }

@Composable
private fun GestureHudView(hud: GestureHud) {
    Column(
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.68f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (hud) {
            is GestureHud.Seek -> {
                Text(
                    text = formatTime(hud.targetMs),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (hud.forward) "快进" else "快退",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            is GestureHud.Level -> {
                Text(
                    text = if (hud.kind == GestureLevelKind.BRIGHTNESS) {
                        "亮度 ${(hud.fraction * 100f).roundToInt()}%"
                    } else {
                        "音量 ${(hud.fraction * 100f).roundToInt()}%"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .width(160.dp)
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(hud.fraction.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(EvPrimary, RoundedCornerShape(2.dp)),
                    )
                }
            }

            GestureHud.Boost -> Text(
                text = "3.0X 加速中",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

// ---------------------------------------------------------------- 亮度

private fun currentWindowBrightness(activity: Activity?): Float {
    if (activity == null) return 0.5f
    val windowBrightness = activity.window.attributes.screenBrightness
    if (windowBrightness >= 0f) return windowBrightness.coerceIn(MIN_BRIGHTNESS, 1f)
    val systemBrightness = runCatching {
        Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(128)
    return (systemBrightness / 255f).coerceIn(MIN_BRIGHTNESS, 1f)
}

private fun applyWindowBrightness(activity: Activity?, value: Float) {
    activity ?: return
    val attributes = activity.window.attributes
    attributes.screenBrightness = value.coerceIn(MIN_BRIGHTNESS, 1f)
    activity.window.attributes = attributes
}

private const val HUD_AUTO_HIDE_MS = 1_000L
private const val BRIGHTNESS_ZONE_FRACTION = 0.25f
private const val VOLUME_ZONE_START = 0.75f
private const val MIN_BRIGHTNESS = 0.01f
