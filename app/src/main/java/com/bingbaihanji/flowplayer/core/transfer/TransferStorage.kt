package com.bingbaihanji.flowplayer.core.transfer

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * WiFi 传输目录管理。
 *
 * 目录选择顺序：
 * 1. 公共下载目录 `Download/FlowPlayer/Transfer`（需要存储权限，写入失败时自动回退）；
 * 2. 应用外部私有目录 `Android/data/<package>/files/Transfer`；
 * 3. 应用内部目录（极端情况兜底，保证服务仍可工作）。
 *
 * 安全约定：
 * - 上传文件名必须是“纯文件名”，拒绝 `..`、路径分隔符、控制字符、绝对路径/盘符路径；
 * - 下载/删除接口只接受规范化后仍位于传输根目录内的普通文件，临时目录不可访问；
 * - 重名文件通过 `name (1).ext` 递增后缀保存，绝不覆盖已有文件。
 */
class TransferStorage(context: Context) {

    /** 重名文件检查与 rename 的互斥锁，避免并发上传选中同一个目标名。 */
    private val nameLock = Any()

    /** 实际使用的传输根目录（构造时确定，避免运行中路径漂移）。 */
    val root: File = resolveRoot(context.applicationContext)

    val rootPath: String
        get() = root.absolutePath

    /** 传输目录所在分区的剩余字节数；读取失败时返回 0。 */
    val freeSpaceBytes: Long
        get() {
            val usable = runCatching { root.usableSpace }.getOrDefault(0L)
            if (usable > 0L) return usable
            return runCatching { StatFs(root.absolutePath).availableBytes }
                .getOrDefault(0L)
                .coerceAtLeast(0L)
        }

    // ---------------------------------------------------------------- 目录与列表

    /**
     * 列出根目录下的普通文件（不递归，排除临时目录）。
     * 返回的 [TransferFile.path] 为绝对路径，App 侧可直接打开文件。
     */
    fun listFiles(): List<TransferFile> {
        val children = try {
            root.listFiles()
        } catch (t: Throwable) {
            Log.w(TAG, "读取传输目录失败：${root.absolutePath}", t)
            null
        } ?: return emptyList()

        return children.asSequence()
            .filter {
                it.isFile &&
                    it.name != TEMP_DIR_NAME &&
                    !it.name.startsWith(".write_probe_")
            }
            .map { file ->
                TransferFile(
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    mimeType = mimeTypeFor(file.name),
                )
            }
            .sortedWith(
                compareByDescending<TransferFile> { it.lastModified }
                    .thenBy { it.name.lowercase() },
            )
            .toList()
    }

    /**
     * 清理上次残留的上传临时文件。只应在 HTTP 服务启动前调用。
     */
    fun cleanupTempFiles() {
        val dir = File(root, TEMP_DIR_NAME)
        if (!dir.isDirectory) return
        val children = try {
            dir.listFiles()
        } catch (t: Throwable) {
            Log.w(TAG, "读取临时目录失败", t)
            return
        } ?: return
        children.forEach { child ->
            runCatching {
                if (child.isDirectory) child.deleteRecursively() else child.delete()
            }.onFailure { Log.w(TAG, "清理临时文件失败：${child.name}", it) }
        }
    }

    // ---------------------------------------------------------------- 上传文件名与落盘

    /**
     * 校验并规范化上传文件名。返回 null 表示拒绝该文件名。
     *
     * 浏览器通常只发送 basename；如果发送了 Windows 全路径或含 `..`，
     * 一律拒绝而不是“猜测”用户意图，避免路径穿越与平台差异。
     */
    fun sanitizeFileName(raw: String?): String? {
        if (raw == null) return null
        var name = raw.trim()
        if (name.isEmpty()) return null
        if (name == "." || name == "..") return null
        if (name.contains("..")) return null
        if (name.contains('/') || name.contains('\\')) return null
        if (name.any { it.code < 0x20 || it.code == 0x7F }) return null
        if (DRIVE_PREFIX_REGEX.containsMatchIn(name)) return null
        if (name == TEMP_DIR_NAME) return null
        if (name.startsWith(".write_probe_")) return null

        // 去掉尾部的空格和点，避免文件移动到 Windows 电脑时出现无法保存的名称。
        name = name.trimEnd(' ', '.')
        if (name.isEmpty() || name == "." || name == "..") return null

        return limitNameBytes(name)
    }

    /**
     * 创建上传临时文件。临时文件位于根目录内的隐藏目录，
     * 完成后再用 rename 移入根目录，保证不会出现“半个文件”。
     */
    @Throws(IOException::class)
    fun createTempFile(): File {
        val dir = tempDirectory()
        val file = File(dir, "upload_${UUID.randomUUID()}.part")
        if (!file.createNewFile()) {
            throw IOException("无法创建上传临时文件")
        }
        return file
    }

    /**
     * 把临时文件移动到根目录，自动处理重名与文件名净化。
     * 返回最终落盘的文件。
     */
    @Throws(IOException::class)
    fun commitTempFile(temp: File, rawName: String): File {
        val safeName = sanitizeFileName(rawName) ?: throw IOException("文件名不合法")
        if (!root.exists() && !root.mkdirs()) throw IOException("无法创建传输目录")

        synchronized(nameLock) {
            val (stem, extension) = splitExtension(safeName)
            var target = File(root, safeName)
            var index = 1
            while (target.exists()) {
                if (index > MAX_RENAME_ATTEMPTS) throw IOException("同名文件过多，无法保存")
                target = File(root, buildSuffixedName(stem, extension, index))
                index++
            }

            val renamed = temp.renameTo(target)
            if (renamed) return target

            // 极少数文件系统不支持 rename 时退化为复制；失败会删除残留目标文件。
            try {
                temp.copyTo(target, overwrite = false)
                temp.delete()
            } catch (t: Throwable) {
                target.delete()
                throw IOException("保存文件失败：$safeName", t)
            }
            return target
        }
    }

    // ---------------------------------------------------------------- 路径校验 / 删除

    /**
     * 校验 HTTP API 传入的相对路径，返回根目录内的普通文件；非法路径返回 null。
     *
     * 路由 tailcard 已做 URL 解码，这里再做一次完整校验：
     * 拒绝 `..`、反斜杠、控制字符、盘符路径和绝对路径。
     */
    fun resolveApiPath(rawPath: String?): File? {
        val path = rawPath?.trim().orEmpty()
        if (path.isEmpty()) return null
        if (isUnsafePath(path)) return null
        // API 路径必须是相对根目录的路径，绝对路径一律拒绝。
        if (path.startsWith("/")) return null
        return findInsideRoot(File(root, path))
    }

    /**
     * 校验 App 侧带回来的文件路径（通常是绝对路径），只允许根目录内的普通文件。
     */
    fun resolveManagedFile(path: String): File? {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || isUnsafePath(trimmed)) return null
        val candidate = if (File(trimmed).isAbsolute) File(trimmed) else File(root, trimmed)
        return findInsideRoot(candidate)
    }

    /**
     * 删除 App 列表中的文件。文件不存在、越界或删除失败都返回 false。
     */
    fun deleteManagedFile(file: TransferFile): Boolean {
        val target = resolveManagedFile(file.path)
        if (target == null) {
            Log.w(TAG, "拒绝删除传输根目录之外的文件：${file.path}")
            return false
        }
        return try {
            val deleted = target.delete()
            if (!deleted) Log.w(TAG, "删除文件失败：${file.path}")
            deleted
        } catch (t: Throwable) {
            Log.w(TAG, "删除文件失败：${file.path}", t)
            false
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private fun tempDirectory(): File {
        if (!root.exists() && !root.mkdirs()) throw IOException("无法创建传输目录")
        val dir = File(root, TEMP_DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建临时目录")
        if (!dir.isDirectory) throw IOException("临时目录不可用")
        return dir
    }

    private fun isUnsafePath(path: String): Boolean =
        path.contains("..") ||
            path.contains('\\') ||
            path.any { it.code < 0x20 || it.code == 0x7F } ||
            DRIVE_PREFIX_REGEX.containsMatchIn(path)

    /**
     * 规范化路径并检查：
     * - 不等于根目录本身；
     * - 位于根目录前缀内（canonicalPath 会解析符号链接，防止软链接逃逸）；
     * - 不在临时目录内；
     * - 是普通文件（目录不可下载/删除）。
     */
    private fun findInsideRoot(candidate: File): File? {
        return try {
            val canonicalRoot = root.canonicalFile
            val resolved = candidate.canonicalFile
            val rootPrefix = canonicalRoot.path + File.separator
            val tempPrefix = File(canonicalRoot, TEMP_DIR_NAME).canonicalFile.path + File.separator
            when {
                resolved == canonicalRoot -> null
                !resolved.path.startsWith(rootPrefix) -> null
                resolved.path.startsWith(tempPrefix) -> null
                resolved.isFile -> resolved
                else -> null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "校验文件路径失败：${candidate.path}", t)
            null
        }
    }

    private fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    }

    /** 生成带递增后缀的文件名，并把总名称控制在文件系统可接受的长度内。 */
    private fun buildSuffixedName(stem: String, extension: String, index: Int): String {
        val suffix = " ($index)"
        var truncatedStem = stem
        while (
            truncatedStem.isNotEmpty() &&
            (truncatedStem + suffix + extension).toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES
        ) {
            truncatedStem = truncatedStem.dropLast(1)
            // 避免把一个代理对从中间截断。
            if (truncatedStem.isNotEmpty() && truncatedStem.last().isHighSurrogate()) {
                truncatedStem = truncatedStem.dropLast(1)
            }
        }
        val name = (truncatedStem + suffix + extension).trimEnd(' ', '.')
        if (name.isEmpty() || name.contains("..") || name.contains('/') || name.contains('\\')) {
            throw IOException("无法生成安全的文件名")
        }
        return name
    }

    /**
     * 限制文件名的 UTF-8 字节数（ext4/F2FS 单文件名上限约 255 字节）。
     * 优先保留扩展名，再截断主文件名。
     */
    private fun limitNameBytes(name: String): String? {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return name

        val dot = name.lastIndexOf('.')
        var stem = if (dot > 0) name.substring(0, dot) else name
        var extension = if (dot > 0) name.substring(dot) else ""
        if (extension.toByteArray(Charsets.UTF_8).size > MAX_EXTENSION_BYTES) {
            // 扩展名本身过长时放弃扩展名，避免把主文件名挤没。
            extension = ""
        }
        if (stem.isEmpty()) return null

        val maxStemBytes = (MAX_NAME_BYTES - extension.toByteArray(Charsets.UTF_8).size).coerceAtLeast(1)
        var cut = stem.length
        while (cut > 0) {
            val prefix = stem.substring(0, cut)
            if (prefix.toByteArray(Charsets.UTF_8).size <= maxStemBytes) break
            cut--
        }
        if (cut > 0 && cut < stem.length && stem[cut - 1].isHighSurrogate()) {
            cut--
        }
        stem = stem.substring(0, cut).trimEnd(' ', '.')
        if (stem.isEmpty()) return null
        return stem + extension
    }

    fun mimeTypeFor(fileName: String, fallback: String? = null): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
        }
        val cleanedFallback = fallback?.substringBefore(';')?.trim().orEmpty()
        return if (cleanedFallback.contains('/')) cleanedFallback else OCTET_STREAM
    }

    companion object {
        private const val TAG = "TransferStorage"

        /** 单文件上传上限：4GB。 */
        const val MAX_FILE_SIZE: Long = 4L * 1024L * 1024L * 1024L

        /** 上传临时文件目录（位于根目录内，保证 rename 不跨分区）。 */
        const val TEMP_DIR_NAME = ".flowplayer_tmp"

        private const val MAX_NAME_BYTES = 240
        private const val MAX_EXTENSION_BYTES = 48
        private const val MAX_RENAME_ATTEMPTS = 9_999
        private const val OCTET_STREAM = "application/octet-stream"

        private val DRIVE_PREFIX_REGEX = Regex("^[A-Za-z]:")

        private fun resolveRoot(context: Context): File {
            val publicRoot = runCatching {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(File(downloads, "FlowPlayer"), "Transfer")
            }.getOrNull()
            if (publicRoot != null && ensureWritable(publicRoot)) {
                Log.i(TAG, "使用公共下载目录：${publicRoot.absolutePath}")
                return publicRoot
            }

            val externalRoot = context.getExternalFilesDir("Transfer")
            if (externalRoot != null && ensureWritable(externalRoot)) {
                Log.i(TAG, "回退到应用外部目录：${externalRoot.absolutePath}")
                return externalRoot
            }

            val internalRoot = File(context.filesDir, "Transfer")
            ensureWritable(internalRoot)
            Log.w(TAG, "回退到应用内部目录：${internalRoot.absolutePath}")
            return internalRoot
        }

        /** 通过试写探针文件确认目录真实可写（mkdirs 成功不代表后续 IO 不抛 EACCES）。 */
        private fun ensureWritable(dir: File): Boolean {
            return try {
                if (!dir.exists() && !dir.mkdirs()) return false
                if (!dir.isDirectory) return false
                val probe = File(dir, ".write_probe_${System.nanoTime()}")
                val created = probe.createNewFile()
                if (created) {
                    runCatching { probe.delete() }.onFailure { Log.w(TAG, "删除写入探针失败", it) }
                }
                created
            } catch (t: Throwable) {
                Log.w(TAG, "传输目录不可写：${dir.absolutePath}", t)
                false
            }
        }
    }
}
