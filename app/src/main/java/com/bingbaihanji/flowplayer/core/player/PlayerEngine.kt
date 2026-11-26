package com.bingbaihanji.flowplayer.core.player

/**
 * 播放引擎。
 *
 * MEDIA3 负责常见容器/编码，走平台 MediaCodec；VLC 用于旧格式兼容与兜底。
 */
enum class PlaybackEngine {
    MEDIA3,
    VLC,
}

/**
 * 按队列选择播放引擎。
 *
 * 这里采用“队列级选择”而不是逐条切换：只要队列中有旧格式，整条队列交给 LibVLC，
 * 避免播放过程中 Media3/LibVLC 频繁切换导致的队列、进度和后台会话断裂。
 */
object PlayerEngineSelector {

    private val legacyVideoExtensions = setOf(
        "rm", "rmvb", "wmv", "asf", "avi", "divx", "xvid", "flv",
        "mpg", "mpeg", "mpe", "m2v", "vob", "m2ts", "ts",
    )

    private val legacyAudioExtensions = setOf(
        "wma", "ra", "rm", "ape", "tta", "dts", "ac3", "wv",
    )

    private val legacyMimePrefixes = setOf(
        "video/x-msvideo",
        "video/x-ms-wmv",
        "video/x-ms-asf",
        "video/vnd.rn-realvideo",
        "application/vnd.rn-realmedia",
        "video/x-flv",
        "video/mpeg",
        "video/mp2t",
        "audio/x-ms-wma",
        "audio/x-real-audio",
        "audio/x-ape",
        "audio/x-tta",
        "audio/vnd.dts",
    )

    fun select(request: PlaybackRequest): PlaybackEngine {
        if (request.items.any { isLegacy(it) }) return PlaybackEngine.VLC
        return PlaybackEngine.MEDIA3
    }

    fun isLegacy(item: PlayerQueueItem): Boolean {
        val mime = item.mimeType.lowercase()
        if (legacyMimePrefixes.any { mime.startsWith(it) }) return true
        val source = item.path.ifBlank { item.uri }
        val extension = source
            .substringBefore('?')
            .substringAfterLast('.', "")
            .lowercase()
        return extension in legacyVideoExtensions || extension in legacyAudioExtensions
    }
}
