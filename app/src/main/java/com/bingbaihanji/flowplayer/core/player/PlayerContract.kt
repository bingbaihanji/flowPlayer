package com.bingbaihanji.flowplayer.core.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

/**
 * 播放页 Intent 契约。
 *
 * 文件库/历史/播放列表只依赖本对象与 [PlayerLauncher]，不直接引用 feature/player 的 Activity，
 * 避免 feature 之间的横向依赖。
 */
object PlayerContract {

    const val ACTIVITY_CLASS = "com.bingbaihanji.flowplayer.feature.player.PlayerActivity"
    const val VLC_ACTIVITY_CLASS = "com.bingbaihanji.flowplayer.feature.player.VlcPlayerActivity"
    const val ACTION_PLAY = "com.bingbaihanji.flowplayer.action.PLAY"

    const val EXTRA_URIS = "player.extra.uris"
    const val EXTRA_PATHS = "player.extra.paths"
    const val EXTRA_TITLES = "player.extra.titles"
    const val EXTRA_MIME_TYPES = "player.extra.mime_types"
    const val EXTRA_DURATIONS = "player.extra.durations"
    const val EXTRA_START_INDEX = "player.extra.start_index"
    const val EXTRA_START_POSITION = "player.extra.start_position"
    const val EXTRA_QUEUE_TITLE = "player.extra.queue_title"
    const val EXTRA_SAVE_HISTORY = "player.extra.save_history"

    /**
     * 生成 Media3 可读取的 URI 字符串：优先 content URI，否则把普通文件路径转成 file URI。
     */
    fun uriFor(path: String, contentUri: String? = null): String {
        if (!contentUri.isNullOrBlank()) return contentUri
        if (path.startsWith("content://") || path.startsWith("file://")) return path
        return Uri.fromFile(File(path)).toString()
    }

    /**
     * 外挂字幕扩展名到 ExoPlayer MIME 的映射。
     */
    fun subtitleMimeFor(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        "ttml", "xml" -> "application/ttml+xml"
        else -> null
    }

    fun newIntent(
        context: Context,
        request: PlaybackRequest,
        engine: PlaybackEngine = PlaybackEngine.MEDIA3,
    ): Intent {
        val items = request.items
        val targetClass = when (engine) {
            PlaybackEngine.MEDIA3 -> ACTIVITY_CLASS
            PlaybackEngine.VLC -> VLC_ACTIVITY_CLASS
        }
        return Intent().apply {
            setClassName(context.packageName, targetClass)
            action = ACTION_PLAY
            putStringArrayListExtra(EXTRA_URIS, ArrayList(items.map { it.uri }))
            putStringArrayListExtra(EXTRA_PATHS, ArrayList(items.map { it.path }))
            putStringArrayListExtra(EXTRA_TITLES, ArrayList(items.map { it.title }))
            putStringArrayListExtra(EXTRA_MIME_TYPES, ArrayList(items.map { it.mimeType }))
            putExtra(EXTRA_DURATIONS, items.map { it.durationMs }.toLongArray())
            putExtra(EXTRA_START_INDEX, request.safeStartIndex)
            putExtra(EXTRA_START_POSITION, request.startPositionMs)
            putExtra(EXTRA_QUEUE_TITLE, request.queueTitle)
            putExtra(EXTRA_SAVE_HISTORY, request.saveHistory)
        }
    }

    fun read(intent: Intent?): PlaybackRequest? {
        intent ?: return null
        val uris = intent.getStringArrayListExtra(EXTRA_URIS) ?: return null
        if (uris.isEmpty()) return null
        val paths = intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        val mimes = intent.getStringArrayListExtra(EXTRA_MIME_TYPES).orEmpty()
        val durations = intent.getLongArrayExtra(EXTRA_DURATIONS) ?: LongArray(0)
        val items = uris.indices.map { index ->
            PlayerQueueItem(
                uri = uris[index],
                path = paths.getOrNull(index) ?: uris[index],
                title = titles.getOrNull(index).orEmpty(),
                mimeType = mimes.getOrNull(index).orEmpty(),
                durationMs = durations.getOrNull(index) ?: 0L,
            )
        }
        return PlaybackRequest(
            items = items,
            startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0),
            startPositionMs = intent.getLongExtra(EXTRA_START_POSITION, 0L),
            queueTitle = intent.getStringExtra(EXTRA_QUEUE_TITLE).orEmpty(),
            saveHistory = intent.getBooleanExtra(EXTRA_SAVE_HISTORY, true),
        )
    }

    /**
     * 系统文件管理器 VIEW Intent 的单条播放请求。
     */
    fun fromViewIntent(intent: Intent, context: Context? = null): PlaybackRequest? {
        val uri = intent.data ?: return null
        val displayName = context?.let { ctx ->
            runCatching {
                ctx.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
            }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        val title = displayName
            ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        return PlaybackRequest(
            items = listOf(
                PlayerQueueItem(
                    uri = uri.toString(),
                    path = uri.toString(),
                    title = title.ifBlank { "视频" },
                    mimeType = intent.type.orEmpty(),
                ),
            ),
            queueTitle = title,
        )
    }
}

/**
 * 启动播放页的唯一入口。
 */
object PlayerLauncher {

    private const val TAG = "PlayerLauncher"

    /**
     * 单次 Intent 最多携带的队列项数。
     *
     * 大相册/大文件夹一次播放可能有上千项，全量塞入 Intent extras 会触发
     * TransactionTooLargeException（Binder 事务缓冲约 1MB）。
     */
    private const val MAX_INTENT_QUEUE_ITEMS = 300

    fun start(
        context: Context,
        request: PlaybackRequest,
        engineOverride: PlaybackEngine? = null,
    ) {
        if (request.items.isEmpty()) return
        // 引擎选择必须基于裁剪前的完整队列：旧格式文件可能落在点击项附近的可播放窗口之外，
        // 若先裁剪再选择，会误判为 Media3，导致整条队列播放失败。
        val engine = engineOverride ?: PlayerEngineSelector.select(request)
        // 以点击项为中心保留最多 MAX_INTENT_QUEUE_ITEMS 项；原队列不超限时保持原样。
        val intentRequest = if (request.items.size <= MAX_INTENT_QUEUE_ITEMS) {
            request
        } else {
            val clickedIndex = request.safeStartIndex
            // 窗口尽量让点击项居中；靠近队列首尾时整体平移，仍保持窗口长度不变。
            val windowStart = (clickedIndex - MAX_INTENT_QUEUE_ITEMS / 2)
                .coerceIn(0, request.items.size - MAX_INTENT_QUEUE_ITEMS)
            val windowEnd = windowStart + MAX_INTENT_QUEUE_ITEMS
            request.copy(
                items = request.items.subList(windowStart, windowEnd),
                startIndex = clickedIndex - windowStart,
            )
        }
        val intent = PlayerContract.newIntent(context, intentRequest, engine).apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        // 启动失败（如系统限制后台启动 Activity）只记录日志，不让宿主页面崩溃。
        runCatching { context.startActivity(intent) }
            .onFailure { error -> Log.e(TAG, "启动播放页失败", error) }
    }
}
