package com.bingbaihanji.flowplayer.feature.library

import com.bingbaihanji.flowplayer.core.data.file.FileSortSpec
import com.bingbaihanji.flowplayer.core.data.file.MediaBucket
import com.bingbaihanji.flowplayer.core.data.file.MediaFile
import com.bingbaihanji.flowplayer.core.data.file.MediaKind

/** 列表 / 网格展示方式。 */
enum class LibraryViewMode {
    LIST,
    GRID,
}

/** 当前浏览位置。 */
sealed interface LibraryLocation {

    /** 文件库根视图：媒体相册 + 常用文件夹。 */
    data object Root : LibraryLocation

    /** MediaStore 相册内部。 */
    data class Bucket(val bucketId: Long, val bucketName: String) : LibraryLocation

    /** 文件系统目录内部。 */
    data class Directory(val path: String) : LibraryLocation
}

/**
 * 面包屑。[isRoot] 表示点击后回到媒体根视图；[directoryPath] 表示点击后跳到指定目录。
 * 两者都为 null 时表示当前项（不可点击）。
 */
data class LibraryCrumb(
    val label: String,
    val isRoot: Boolean = false,
    val directoryPath: String? = null,
) {
    val hasTarget: Boolean
        get() = isRoot || directoryPath != null
}

/** 当前列表的统计信息，用于“数量/大小/时长”展示。 */
data class LibrarySummary(
    val albumCount: Int = 0,
    val folderCount: Int = 0,
    val directoryCount: Int = 0,
    val fileCount: Int = 0,
    val totalSize: Long = 0L,
    val totalDurationMs: Long = 0L,
)

data class LibraryUiState(
    val permissionGranted: Boolean = false,
    val kind: MediaKind = MediaKind.VIDEO,
    val location: LibraryLocation = LibraryLocation.Root,
    val buckets: List<MediaBucket> = emptyList(),
    val folders: List<MediaFile> = emptyList(),
    val files: List<MediaFile> = emptyList(),
    val crumbs: List<LibraryCrumb> = emptyList(),
    val sort: FileSortSpec = FileSortSpec(),
    val viewMode: LibraryViewMode = LibraryViewMode.LIST,
    val showHidden: Boolean = false,
    val searchQuery: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val summary: LibrarySummary = LibrarySummary(),
    val storageRootPath: String = "",
) {
    val isSearchActive: Boolean
        get() = searchQuery.isNotBlank()
}
