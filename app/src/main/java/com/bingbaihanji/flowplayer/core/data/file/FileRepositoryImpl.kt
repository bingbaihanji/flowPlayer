package com.bingbaihanji.flowplayer.core.data.file

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.*

/**
 * [FileRepository] 的默认实现：MediaStore（相册/搜索）+ 文件系统（目录浏览）+ 本地文件操作。
 *
 * 说明：
 * - MediaStore 的 `date_modified` 单位是秒，内部统一转换成毫秒；
 * - 所有查询失败（无权限、厂商 MediaProvider 差异等）都会降级为空列表/失败结果；
 * - 不使用 SAF，根目录/相册依赖 `MANAGE_EXTERNAL_STORAGE` 或旧版读写权限。
 */
class FileRepositoryImpl(private val context: Context) : FileRepository {

    private val resolver get() = context.contentResolver

    private val storageRoot: File
        get() = Environment.getExternalStorageDirectory()

    override val storageRootPath: String
        get() = storageRoot.absolutePath

    override suspend fun loadBuckets(
        kind: MediaKind,
        sort: FileSortSpec,
    ): List<MediaBucket> = withContext(Dispatchers.IO) {
        val spec = collectionSpec(kind) ?: return@withContext emptyList()
        val buckets = LinkedHashMap<Long, BucketAccumulator>()
        try {
            // 按修改时间倒序，保证每个 bucket 第一次出现的条目就是最新封面。
            resolver.query(
                spec.uri,
                projection(spec.hasDuration),
                null,
                null,
                "date_modified DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.longOrNull("_id")
                    val dataPath = cursor.stringOrNull("_data")?.takeIf { it.isNotBlank() }
                    val contentUri = id?.let { mediaContentUri(spec, it) }
                    val path = dataPath ?: contentUri ?: continue

                    val bucketId = cursor.longOrNull("bucket_id") ?: UNKNOWN_BUCKET_ID
                    val bucketName = cursor.bucketDisplayName(dataPath)
                    val displayName = cursor.stringOrNull("_display_name")
                    val size = cursor.longOrNull("_size") ?: 0L
                    val modified = mediaModifiedMillis(cursor)
                    val duration = if (spec.hasDuration) cursor.longOrNull("duration") ?: 0L else 0L
                    val mimeType = cursor.stringOrNull("mime_type")

                    val title = displayName?.takeIf { it.isNotBlank() }
                        ?: dataPath?.let { File(it).name }?.takeIf { it.isNotBlank() }
                        ?: "未知文件"

                    val cover = MediaFile(
                        path = path,
                        title = title,
                        kind = kind,
                        size = size,
                        lastModified = modified,
                        mimeType = mimeType,
                        durationMs = if (spec.hasDuration) duration else null,
                        bucketId = bucketId,
                        contentUri = contentUri,
                    )

                    val accumulator = buckets.getOrPut(bucketId) { BucketAccumulator(bucketName) }
                    accumulator.count++
                    accumulator.totalSize += size
                    accumulator.totalDurationMs += duration
                    accumulator.latestModified = maxOf(accumulator.latestModified, modified)
                    if (accumulator.cover == null) {
                        accumulator.cover = cover
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadBuckets(${kind.name}) failed", e)
            return@withContext emptyList()
        }

        buckets.map { (id, accumulator) ->
            MediaBucket(
                id = id,
                name = accumulator.name,
                kind = kind,
                count = accumulator.count,
                totalSize = accumulator.totalSize,
                totalDurationMs = accumulator.totalDurationMs,
                latestModified = accumulator.latestModified,
                cover = accumulator.cover,
            )
        }.sortedWith(sort.bucketComparator())
    }

    override suspend fun loadRootFolders(
        kind: MediaKind,
        sort: FileSortSpec,
        includeHidden: Boolean,
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        val folders = mutableListOf<MediaFile>()

        fun addFolder(folder: File?, label: String) {
            if (folder == null) return
            try {
                if (!folder.exists() || !folder.isDirectory) return
                if (!includeHidden && folder.name.startsWith(".")) return
                folders += MediaFile(
                    path = folder.absolutePath,
                    title = label,
                    kind = MediaKind.OTHER,
                    isDirectory = true,
                    lastModified = folder.lastModified(),
                    childCount = countChildren(folder, kind, includeHidden),
                )
            } catch (e: Exception) {
                Log.w(TAG, "add root folder failed: ${folder.absolutePath}", e)
            }
        }

        addFolder(storageRoot, "内部存储")
        addFolder(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "下载",
        )
        addFolder(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "相机",
        )
        addFolder(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "图片",
        )
        addFolder(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "视频",
        )
        addFolder(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "音乐",
        )
        addFolder(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "文档",
        )

        folders.distinctBy { it.path }.sortedWith(sort.fileComparator())
    }

    override suspend fun loadBucketFiles(
        kind: MediaKind,
        bucketId: Long,
        sort: FileSortSpec,
        includeHidden: Boolean,
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        val spec = collectionSpec(kind) ?: return@withContext emptyList()
        val selection: String
        val selectionArgs: Array<String>?
        // MediaStore 的 bucket_id 本身可能为负数（哈希结果），只有哨兵值才表示“无 bucket”。
        if (bucketId == UNKNOWN_BUCKET_ID) {
            selection = "bucket_id IS NULL"
            selectionArgs = null
        } else {
            selection = "bucket_id=?"
            selectionArgs = arrayOf(bucketId.toString())
        }
        queryMedia(spec, selection, selectionArgs, sort, includeHidden)
    }

    override suspend fun searchMedia(
        kind: MediaKind,
        query: String,
        sort: FileSortSpec,
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        val spec = collectionSpec(kind) ?: return@withContext emptyList()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        queryMedia(
            spec = spec,
            selection = "_display_name LIKE ?",
            selectionArgs = arrayOf("%$trimmed%"),
            sort = sort,
            includeHidden = true,
        )
    }

    override suspend fun listDirectory(
        path: String,
        kind: MediaKind,
        sort: FileSortSpec,
        includeHidden: Boolean,
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        val directory = File(path)
        if (!directory.exists() || !directory.isDirectory) return@withContext emptyList()
        val children = try {
            directory.listFiles()
        } catch (e: Exception) {
            Log.w(TAG, "listDirectory failed: $path", e)
            null
        } ?: return@withContext emptyList()

        children.asSequence()
            .filter { child -> includeHidden || !child.name.startsWith(".") }
            .filter { child -> child.isDirectory || kindForName(child.name) == kind }
            .map { child ->
                MediaFile(
                    path = child.absolutePath,
                    title = child.name,
                    kind = if (child.isDirectory) MediaKind.OTHER else kind,
                    isDirectory = child.isDirectory,
                    size = if (child.isDirectory) 0L else child.length(),
                    lastModified = child.lastModified(),
                    mimeType = if (child.isDirectory) null else mimeTypeForName(child.name),
                )
            }
            .toList()
            .sortedWith(sort.fileComparator())
    }

    override suspend fun rename(file: MediaFile, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (file.isDirectory) throw IOException("暂不支持重命名文件夹")
                val trimmed = newName.trim()
                if (trimmed.isEmpty()) throw IllegalArgumentException("文件名不能为空")
                if (trimmed.contains('/') || trimmed.contains('\\')) {
                    throw IllegalArgumentException("文件名不能包含斜杠")
                }
                val finalName = ensureExtension(trimmed, file.title)
                val realFile = File(file.path)
                if (realFile.exists() && realFile.isFile) {
                    val parent = realFile.parentFile ?: throw IOException("无法访问文件所在目录")
                    val target = File(parent, finalName)
                    if (target.absolutePath == realFile.absolutePath) return@runCatching
                    if (target.exists()) throw IOException("同名文件已存在")
                    if (realFile.renameTo(target)) {
                        // 同时扫描旧路径与新路径，尽量让 MediaStore 删除旧记录。
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(realFile.absolutePath, target.absolutePath),
                            null,
                            null,
                        )
                    } else if (!updateMediaStoreDisplayName(file, finalName)) {
                        throw IOException("重命名失败，文件可能只读或没有权限")
                    }
                } else if (!updateMediaStoreDisplayName(file, finalName)) {
                    throw IOException("重命名失败，文件可能不存在或没有权限")
                }
            }
        }

    override suspend fun delete(file: MediaFile): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (file.isDirectory) throw IOException("暂不支持删除文件夹")
            var removed = false
            val uriString = file.contentUri?.takeIf { it.startsWith("content://") }
            if (uriString != null) {
                try {
                    removed = resolver.delete(uriString.toUri(), null, null) > 0
                } catch (e: Exception) {
                    Log.w(TAG, "delete via MediaStore failed: ${file.path}", e)
                }
            }
            if (!removed) {
                val realFile = File(file.path)
                if (realFile.exists()) {
                    if (!realFile.delete()) {
                        throw IOException("删除失败，文件可能只读或没有权限")
                    }
                    removed = true
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(realFile.absolutePath),
                        null,
                        null,
                    )
                }
            }
            if (!removed) throw IOException("删除失败，文件可能不存在或没有权限")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // MediaStore helpers
    // ---------------------------------------------------------------------------------------------

    private fun collectionSpec(kind: MediaKind): CollectionSpec? = when (kind) {
        MediaKind.VIDEO -> CollectionSpec(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaKind.VIDEO,
            hasDuration = true,
        )

        MediaKind.IMAGE -> CollectionSpec(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaKind.IMAGE,
            hasDuration = false,
        )

        MediaKind.AUDIO -> CollectionSpec(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MediaKind.AUDIO,
            hasDuration = true,
        )

        MediaKind.OTHER -> null
    }

    private fun projection(hasDuration: Boolean): Array<String> {
        val columns = mutableListOf(
            "_id",
            "bucket_id",
            "bucket_display_name",
            "_data",
            "_display_name",
            "_size",
            "date_modified",
            "mime_type",
        )
        if (hasDuration) columns += "duration"
        return columns.toTypedArray()
    }

    private fun mediaContentUri(spec: CollectionSpec, id: Long): String =
        ContentUris.withAppendedId(spec.uri, id).toString()

    private fun queryMedia(
        spec: CollectionSpec,
        selection: String?,
        selectionArgs: Array<String>?,
        sort: FileSortSpec,
        includeHidden: Boolean,
    ): List<MediaFile> {
        val files = ArrayList<MediaFile>()
        try {
            resolver.query(
                spec.uri,
                projection(spec.hasDuration),
                selection,
                selectionArgs,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val file = cursor.toMediaFile(spec) ?: continue
                    if (!includeHidden && file.looksHidden()) continue
                    files += file
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "query media (${spec.kind.name}) failed", e)
            return emptyList()
        }
        files.sortWith(sort.fileComparator())
        return files
    }

    private fun Cursor.toMediaFile(spec: CollectionSpec): MediaFile? {
        val id = longOrNull("_id")
        val dataPath = stringOrNull("_data")?.takeIf { it.isNotBlank() }
        val contentUri = id?.let { mediaContentUri(spec, it) }
        val path = dataPath ?: contentUri ?: return null
        val displayName = stringOrNull("_display_name")
        val title = displayName?.takeIf { it.isNotBlank() }
            ?: dataPath?.let { File(it).name }?.takeIf { it.isNotBlank() }
            ?: "未知文件"
        return MediaFile(
            path = path,
            title = title,
            kind = spec.kind,
            size = longOrNull("_size") ?: 0L,
            lastModified = mediaModifiedMillis(this),
            mimeType = stringOrNull("mime_type"),
            durationMs = if (spec.hasDuration) longOrNull("duration") else null,
            bucketId = longOrNull("bucket_id"),
            contentUri = contentUri,
        )
    }

    private fun Cursor.bucketDisplayName(dataPath: String?): String {
        val displayName = stringOrNull("bucket_display_name")
        if (!displayName.isNullOrBlank()) return displayName
        val parentName = dataPath?.let { File(it).parentFile?.name }
        return parentName?.takeIf { it.isNotBlank() } ?: "未命名相册"
    }

    private fun mediaModifiedMillis(cursor: Cursor): Long =
        (cursor.longOrNull("date_modified") ?: 0L) * 1000L

    private fun MediaFile.looksHidden(): Boolean {
        if (title.startsWith(".")) return true
        if (path.startsWith("content://")) return false
        return path.substringAfterLast('/').startsWith(".")
    }

    private fun updateMediaStoreDisplayName(file: MediaFile, newName: String): Boolean {
        val uriString = file.contentUri
            ?: file.path.takeIf { it.startsWith("content://") }
            ?: return false
        val values = ContentValues().apply { put("_display_name", newName) }
        return try {
            resolver.update(uriString.toUri(), values, null, null) > 0
        } catch (e: Exception) {
            Log.w(TAG, "update display name failed: ${file.path}", e)
            false
        }
    }

    // ---------------------------------------------------------------------------------------------
    // File system helpers
    // ---------------------------------------------------------------------------------------------

    private fun countChildren(directory: File, kind: MediaKind, includeHidden: Boolean): Int {
        return try {
            directory.listFiles()?.count { child ->
                (includeHidden || !child.name.startsWith(".")) &&
                        (child.isDirectory || kindForName(child.name) == kind)
            } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun kindForName(name: String): MediaKind {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
        if (extension.isEmpty()) return MediaKind.OTHER
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        return kindFromMime(mime) ?: kindFromExtension(extension)
    }

    private fun kindFromMime(mime: String?): MediaKind? {
        if (mime == null) return null
        return when {
            mime.startsWith("video/") -> MediaKind.VIDEO
            mime.startsWith("image/") -> MediaKind.IMAGE
            mime.startsWith("audio/") -> MediaKind.AUDIO
            else -> MediaKind.OTHER
        }
    }

    private fun kindFromExtension(extension: String): MediaKind = when (extension) {
        in VIDEO_EXTENSIONS -> MediaKind.VIDEO
        in IMAGE_EXTENSIONS -> MediaKind.IMAGE
        in AUDIO_EXTENSIONS -> MediaKind.AUDIO
        else -> MediaKind.OTHER
    }

    private fun mimeTypeForName(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
        return if (extension.isEmpty()) {
            null
        } else {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
    }

    private fun ensureExtension(newName: String, oldTitle: String): String {
        if (newName.contains('.')) return newName
        val oldExtension = oldTitle.substringAfterLast('.', "")
        return if (oldTitle.contains('.') && oldExtension.isNotEmpty()) {
            "$newName.$oldExtension"
        } else {
            newName
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Data holders
    // ---------------------------------------------------------------------------------------------

    private data class CollectionSpec(
        val uri: Uri,
        val kind: MediaKind,
        val hasDuration: Boolean,
    )

    private class BucketAccumulator(val name: String) {
        var count: Int = 0
        var totalSize: Long = 0L
        var totalDurationMs: Long = 0L
        var latestModified: Long = 0L
        var cover: MediaFile? = null
    }

    private companion object {
        const val TAG = "FileRepository"
        const val UNKNOWN_BUCKET_ID = -1L

        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v",
            "3gp", "3g2", "mpg", "mpeg", "mpe", "rmvb", "rm", "asf", "vob",
            "f4v", "ogv", "m2ts", "mts", "divx", "xvid",
        )
        val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif",
            "tif", "tiff", "avif", "jfif", "svg", "dng",
        )
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "wav", "flac", "aac", "m4a", "ogg", "oga", "wma", "ape",
            "opus", "amr", "mid", "midi", "mka", "aif", "aiff", "ac3", "dts",
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Cursor utilities (kept outside the class so they are trivially inlined at call sites).
// -------------------------------------------------------------------------------------------------

private fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}

private fun Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}
