package com.bingbaihanji.flowplayer.core.data.file

/**
 * 媒体类型。
 *
 * [OTHER] 仅用于文件夹或暂不支持的普通文件，UI 默认只展示视频/图片/音频。
 */
enum class MediaKind {
    VIDEO,
    IMAGE,
    AUDIO,
    OTHER,
}

/**
 * 文件库统一条目模型：既可能来自 MediaStore，也可能来自文件系统。
 *
 * - [path]：真实文件路径；若系统不暴露路径（Scoped Storage）则可能是 `content://` URI。
 * - [contentUri]：MediaStore 条目对应的 content URI，便于缩略图加载与删除。
 * - [durationMs]：视频/音频时长，文件系统条目可能为 null。
 * - [childCount]：文件夹或相册的直接子项数量（可能为 null，表示未统计）。
 */
data class MediaFile(
    val path: String,
    val title: String,
    val kind: MediaKind,
    val isDirectory: Boolean = false,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val mimeType: String? = null,
    val durationMs: Long? = null,
    val bucketId: Long? = null,
    val childCount: Int? = null,
    val contentUri: String? = null,
)

/**
 * MediaStore 按 bucket 聚合出的相册（专辑）。
 *
 * [cover] 为最近修改的一条媒体，用于列表封面与缩略图。
 */
data class MediaBucket(
    val id: Long,
    val name: String,
    val kind: MediaKind,
    val count: Int,
    val totalSize: Long = 0L,
    val totalDurationMs: Long = 0L,
    val latestModified: Long = 0L,
    val cover: MediaFile? = null,
)
