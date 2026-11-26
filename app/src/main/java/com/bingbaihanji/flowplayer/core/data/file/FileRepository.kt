package com.bingbaihanji.flowplayer.core.data.file

/**
 * 本地媒体/文件仓库。
 *
 * 数据来源分两类：
 * 1. MediaStore：按 bucket 聚合相册、按相册列文件、全局搜索；
 * 2. 文件系统：浏览内部存储与 Download/DCIM 等目录。
 *
 * 所有挂起方法都会自行切换到 IO 线程，失败时尽量返回空列表或 [Result.failure]，
 * 不向 UI 抛出可导致崩溃的异常。
 */
interface FileRepository {

    /** 主外部存储根目录（通常是 /storage/emulated/0）。 */
    val storageRootPath: String

    /** 当前媒体类型的全部 MediaStore 相册，按 [sort] 排序。 */
    suspend fun loadBuckets(kind: MediaKind, sort: FileSortSpec): List<MediaBucket>

    /** 根视图中的文件系统目录（内部存储、下载、相机、图片、视频、音乐、文档）。 */
    suspend fun loadRootFolders(
        kind: MediaKind,
        sort: FileSortSpec,
        includeHidden: Boolean,
    ): List<MediaFile>

    /** 读取某个 MediaStore 相册内的媒体文件。 */
    suspend fun loadBucketFiles(
        kind: MediaKind,
        bucketId: Long,
        sort: FileSortSpec,
        includeHidden: Boolean,
    ): List<MediaFile>

    /** 全局按文件名搜索当前类型的媒体。 */
    suspend fun searchMedia(kind: MediaKind, query: String, sort: FileSortSpec): List<MediaFile>

    /** 浏览文件系统目录：返回子目录 + 当前类型的文件。 */
    suspend fun listDirectory(
        path: String,
        kind: MediaKind,
        sort: FileSortSpec,
        includeHidden: Boolean,
    ): List<MediaFile>

    /** 重命名文件；失败返回带中文提示的 [Result.failure]。 */
    suspend fun rename(file: MediaFile, newName: String): Result<Unit>

    /** 删除文件；失败返回带中文提示的 [Result.failure]。 */
    suspend fun delete(file: MediaFile): Result<Unit>
}
