package com.bingbaihanji.flowplayer.feature.viewer.image

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * 全屏图片查看页。通过 Intent extras 接收一组图片路径，支持左右滑动切换、
 * 双击缩放以及双指缩放/拖动。
 */
class ImageViewerActivity : ComponentActivity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val paths = intent.getStringArrayListExtra(EXTRA_PATHS)
            ?.filterNotNull()
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (paths.isEmpty()) {
            finish()
            return
        }
        val initialIndex = intent.getIntExtra(EXTRA_INDEX, 0)
        val fallbackTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val imageLoader = createImageLoader()

        enableEdgeToEdge()
        window.setBackgroundDrawable(android.graphics.Color.BLACK.toDrawable())
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            ViewerTheme {
                ImageViewerScreen(
                    paths = paths,
                    initialIndex = initialIndex,
                    fallbackTitle = fallbackTitle,
                    imageLoader = imageLoader,
                    onBack = { finish() },
                )
            }
        }
    }

    /** coil-gif 的解码器不会自动注册，这里按版本手动注册，保证 GIF 可以播放动画。 */
    private fun createImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
    }

    companion object {
        /** 全量图片路径，ArrayList<String>。 */
        const val EXTRA_PATHS = "extra_paths"

        /** 初始索引，Int。 */
        const val EXTRA_INDEX = "extra_index"

        /** 标题回退值，String，可选。 */
        const val EXTRA_TITLE = "extra_title"
    }
}

private val ViewerGreen = Color(0xFF79D388)

private val ViewerDarkColors = darkColorScheme(
    primary = ViewerGreen,
    onPrimary = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF141414),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFB6B6B6),
)

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val DEFAULT_TITLE = "图片"

@Composable
private fun ViewerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ViewerDarkColors, content = content)
}

@Composable
private fun ImageViewerScreen(
    paths: List<String>,
    initialIndex: Int,
    fallbackTitle: String,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
) {
    val startIndex = initialIndex.coerceIn(0, paths.lastIndex)
    val pagerState = rememberPagerState(initialPage = startIndex) { paths.size }
    val context = LocalContext.current
    val currentPath = paths.getOrNull(pagerState.currentPage) ?: paths.first()
    val title by produceState(
        initialValue = fallbackTitle.ifBlank { DEFAULT_TITLE },
        key1 = currentPath,
    ) {
        value = withContext(Dispatchers.IO) {
            resolveDisplayName(context, currentPath)
        } ?: fallbackTitle.ifBlank { DEFAULT_TITLE }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
        ) { page ->
            ZoomableImage(
                path = paths[page],
                imageLoader = imageLoader,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ViewerTopBar(
            title = title,
            indexText = "${pagerState.currentPage + 1} / ${paths.size}",
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun ViewerTopBar(
    title: String,
    indexText: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xE6000000),
                    0.65f to Color(0xB3000000),
                    1f to Color.Transparent,
                ),
            )
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = indexText,
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(end = 12.dp),
        )
    }
}

@Composable
private fun ZoomableImage(
    path: String,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
) {
    var scale by remember(path) { mutableFloatStateOf(MIN_SCALE) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var imageState by remember(path) {
        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
            .pointerInput(path) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > MIN_SCALE) {
                            scale = MIN_SCALE
                            offset = Offset.Zero
                        } else {
                            scale = DOUBLE_TAP_SCALE
                            offset = Offset.Zero
                        }
                    },
                )
            }
            .pointerInput(path) {
                // 使用 awaitEachGesture 实现与 detectTransformGestures 等价的缩放/平移；
                // 未放大时不吃掉单指拖动，保证 HorizontalPager 能正常翻页，
                // 双指缩放或已放大时才消费事件。
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val touchSlop = viewConfiguration.touchSlop
                    var pastTouchSlop = false
                    var accumulatedZoom = 1f
                    var accumulatedPan = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val pointerCount = event.changes.count { it.pressed }
                            if (pointerCount >= 2) {
                                // 双指按下后立即拦截父级 Pager 的滑动，避免缩放过程中误翻页。
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) change.consume()
                                }
                            }
                            if (!pastTouchSlop) {
                                accumulatedZoom *= zoomChange
                                accumulatedPan += panChange
                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = abs(1f - accumulatedZoom) * centroidSize
                                if (zoomMotion > touchSlop ||
                                    accumulatedPan.getDistance() > touchSlop
                                ) {
                                    pastTouchSlop = true
                                }
                            }
                            if (pastTouchSlop) {
                                val currentScale = scale
                                val newScale = (currentScale * zoomChange)
                                    .coerceIn(MIN_SCALE, MAX_SCALE)
                                if (pointerCount >= 2 ||
                                    currentScale > MIN_SCALE ||
                                    newScale > MIN_SCALE
                                ) {
                                    if (newScale <= MIN_SCALE) {
                                        scale = MIN_SCALE
                                        offset = Offset.Zero
                                    } else {
                                        val maxX = viewportSize.width * (newScale - 1f) / 2f
                                        val maxY = viewportSize.height * (newScale - 1f) / 2f
                                        scale = newScale
                                        offset = Offset(
                                            x = (offset.x + panChange.x).coerceIn(-maxX, maxX),
                                            y = (offset.y + panChange.y).coerceIn(-maxY, maxY),
                                        )
                                    }
                                    event.changes.forEach { change ->
                                        if (change.positionChanged()) change.consume()
                                    }
                                }
                            }
                        }
                    } while (!canceled && event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = path,
            contentDescription = "图片",
            imageLoader = imageLoader,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            onLoading = { imageState = it },
            onSuccess = { imageState = it },
            onError = { imageState = it },
        )
        when (imageState) {
            is AsyncImagePainter.State.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = ViewerGreen,
                strokeWidth = 2.dp,
            )

            is AsyncImagePainter.State.Error -> Text(
                text = "图片加载失败",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> Unit
        }
    }
}

/** 优先取真实文件名；content uri 通过 OpenableColumns 查询，失败时回退到传入的标题。 */
private fun resolveDisplayName(context: Context, path: String): String? = runCatching {
    val uri = path.toUri()
    when (uri.scheme) {
        ContentResolver.SCHEME_CONTENT -> context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }

        ContentResolver.SCHEME_FILE -> uri.lastPathSegment
        null -> File(path).name
        else -> uri.lastPathSegment
    }
}.getOrNull()?.takeIf { it.isNotBlank() }
