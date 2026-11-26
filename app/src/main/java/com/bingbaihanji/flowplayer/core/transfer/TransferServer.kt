package com.bingbaihanji.flowplayer.core.transfer

import android.content.Context
import android.os.Build
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 内嵌 Ktor CIO 文件传输服务器。
 *
 * - 监听 `0.0.0.0`，端口从 8080 到 8099 依次尝试；
 * - 所有路由都要求 URL 参数 `t` 与本次启动的随机 token 一致；
 * - 上传按 64KB 分块流式写临时文件，不会把整个文件读进内存；
 * - 下载实现单段 HTTP Range（206），多段/非法 Range 退回完整下载；
 * - 路径异常、文件不存在等情况返回 JSON 错误，不向 Ktor 抛未处理异常。
 *
 * 该类只负责 HTTP 层，不持有 Service 引用；文件变化通过 [onFilesChanged] 通知服务刷新状态。
 */
class TransferServer(
    context: Context,
    private val storage: TransferStorage,
    private val token: String,
    private val onFilesChanged: () -> Unit,
) {

    private val appContext: Context = context.applicationContext

    /** 传输页面在 assets 中读取一次后缓存；读取失败时页面请求返回 500。 */
    private val indexHtml: ByteArray? = try {
        appContext.assets.open(INDEX_ASSET_PATH).use { it.readBytes() }
    } catch (t: Throwable) {
        Log.e(TAG, "读取传输页面失败：$INDEX_ASSET_PATH", t)
        null
    }

    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** 实际监听端口；服务停止后为 0。 */
    @Volatile
    var port: Int = 0
        private set

    // ---------------------------------------------------------------- 生命周期

    /**
     * 启动服务器并返回实际端口。会先清理上次残留的临时文件。
     * 8080–8099 全部失败时抛出 [IOException]。
     */
    fun start(): Int {
        storage.cleanupTempFiles()
        var lastFailure: Throwable? = null

        for (candidate in PORT_RANGE) {
            if (!isPortAvailable(candidate)) {
                lastFailure = IOException("端口 $candidate 已被占用")
                continue
            }

            var created: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
            try {
                val createdServer = embeddedServer(CIO, port = candidate, host = BIND_HOST) {
                    installRoutes()
                }
                created = createdServer
                createdServer.start(wait = false)
                server = createdServer
                port = candidate
                Log.i(TAG, "传输服务已启动：$BIND_HOST:$candidate")
                return candidate
            } catch (t: Throwable) {
                lastFailure = t
                Log.w(TAG, "端口 $candidate 启动失败", t)
                // 启动过程中失败时尽力销毁已创建的应用，避免端口循环积累资源。
                runCatching { created?.stop(0, 0) }
                    .onFailure { Log.w(TAG, "清理启动失败的服务器失败", it) }
            }
        }

        throw IOException("端口 $PORT_FIRST-$PORT_LAST 均不可用", lastFailure)
    }

    /**
     * 停止服务器并释放监听端口。可重复调用；异常只记录不抛出。
     */
    fun stop() {
        val current = server ?: return
        server = null
        port = 0
        try {
            current.stop(0, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "停止传输服务失败", t)
        }
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(BIND_HOST, port))
            }
            true
        } catch (t: Throwable) {
            Log.d(TAG, "端口 $port 不可用：${describe(t)}")
            false
        }
    }

    // ---------------------------------------------------------------- 路由

    private fun Application.installRoutes() {
        routing {
            // 传输页面：token 缺失或错误返回 403 文本。
            get("/") {
                if (!requireToken(call, jsonError = false)) return@get
                val html = indexHtml
                if (html == null) {
                    call.respondError(HttpStatusCode.InternalServerError, "传输页面资源缺失")
                    return@get
                }
                try {
                    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                    call.respondBytes(html, ContentType.Text.Html.withCharset(Charsets.UTF_8))
                } catch (t: Throwable) {
                    Log.w(TAG, "返回传输页面失败", t)
                }
            }

            get("/api/info") {
                if (!requireToken(call, jsonError = true)) return@get
                try {
                    call.respondJson(infoJson())
                } catch (t: Throwable) {
                    Log.w(TAG, "读取传输信息失败", t)
                    call.respondError(HttpStatusCode.InternalServerError, "读取传输信息失败：${describe(t)}")
                }
            }

            get("/api/files") {
                if (!requireToken(call, jsonError = true)) return@get
                try {
                    call.respondJson(filesJson())
                } catch (t: Throwable) {
                    Log.w(TAG, "读取文件列表失败", t)
                    call.respondError(HttpStatusCode.InternalServerError, "读取文件列表失败：${describe(t)}")
                }
            }

            post("/api/upload") {
                if (!requireToken(call, jsonError = true)) return@post
                handleUpload(call)
            }

            get("/api/download/{path...}") {
                if (!requireToken(call, jsonError = true)) return@get
                val relativePath = call.parameters.getAll("path")?.joinToString("/").orEmpty()
                val file = storage.resolveApiPath(relativePath)
                if (file == null) {
                    call.respondError(HttpStatusCode.NotFound, "文件不存在或路径不合法")
                    return@get
                }
                try {
                    call.respondDownload(file)
                } catch (t: Throwable) {
                    Log.w(TAG, "下载文件失败：${file.name}", t)
                    call.respondError(HttpStatusCode.InternalServerError, "下载失败：${describe(t)}")
                }
            }

            delete("/api/files/{path...}") {
                if (!requireToken(call, jsonError = true)) return@delete
                val relativePath = call.parameters.getAll("path")?.joinToString("/").orEmpty()
                val file = storage.resolveApiPath(relativePath)
                if (file == null) {
                    call.respondError(HttpStatusCode.NotFound, "文件不存在或路径不合法")
                    return@delete
                }
                try {
                    if (!file.delete()) {
                        call.respondError(HttpStatusCode.InternalServerError, "删除文件失败：${file.name}")
                        return@delete
                    }
                    notifyFilesChanged()
                    call.respondJson("{\"ok\":true}")
                } catch (t: Throwable) {
                    Log.w(TAG, "删除文件失败：${file.name}", t)
                    call.respondError(HttpStatusCode.InternalServerError, "删除失败：${describe(t)}")
                }
            }
        }
    }

    // ---------------------------------------------------------------- 上传

    /**
     * 处理 multipart 多文件上传。
     *
     * 单个文件失败只记录错误，继续处理后续文件；全部失败时按情况返回 400/413。
     * 只要有文件落盘成功，就通知服务刷新状态（通知栏与 UI 列表）。
     */
    private suspend fun handleUpload(call: ApplicationCall) {
        var uploadedCount = 0
        var filePartCount = 0
        var sawTooLarge = false
        val errors = mutableListOf<String>()

        val multipart = try {
            call.receiveMultipart()
        } catch (t: Throwable) {
            Log.w(TAG, "解析上传请求失败", t)
            call.respondError(HttpStatusCode.BadRequest, "上传请求格式不正确：${describe(t)}")
            return
        }

        try {
            multipart.forEachPart { part ->
                try {
                    if (part is PartData.FileItem) {
                        filePartCount++
                        when (val outcome = saveUploadPart(part)) {
                            is UploadOutcome.Saved -> uploadedCount++
                            is UploadOutcome.Failed -> {
                                errors += outcome.message
                                if (outcome.tooLarge) sawTooLarge = true
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "处理上传分段失败", t)
                    errors += "处理上传数据失败：${describe(t)}"
                } finally {
                    // Ktor 要求每个 part 处理后释放；release 会丢弃该 part 未读完的数据。
                    try {
                        part.release()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w(TAG, "释放上传分段失败", t)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "上传流处理中断", t)
            call.respondError(HttpStatusCode.BadGateway, "上传中断：${describe(t)}")
            return
        }

        if (uploadedCount > 0) notifyFilesChanged()

        when {
            filePartCount == 0 -> call.respondError(HttpStatusCode.BadRequest, "没有收到文件")
            uploadedCount > 0 -> call.respondJson(uploadSuccessJson(uploadedCount, errors))
            sawTooLarge -> call.respondError(
                HttpStatusCode.PayloadTooLarge,
                "文件超过 4GB 上限",
                errors,
            )
            else -> call.respondError(HttpStatusCode.BadRequest, "所有文件上传失败", errors)
        }
    }

    /**
     * 把单个上传分段流式写入临时文件，成功后移动到根目录。
     * 超过 4GB 时在达到上限前停止写入，临时文件随后删除。
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun saveUploadPart(part: PartData.FileItem): UploadOutcome {
        val rawName = part.originalFileName
        val safeName = storage.sanitizeFileName(rawName)
            ?: return UploadOutcome.Failed("文件名不合法：${rawName ?: "缺少文件名"}")

        val tempFile = try {
            storage.createTempFile()
        } catch (t: Throwable) {
            Log.w(TAG, "创建上传临时文件失败", t)
            return UploadOutcome.Failed("无法创建临时文件：${describe(t)}")
        }

        var committed = false
        try {
            var uploadedBytes = 0L
            var tooLarge = false
            val buffer = ByteArray(UPLOAD_BUFFER_SIZE)
            val channel = part.provider()

            FileOutputStream(tempFile).use { output ->
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) {
                        // 当前没有可读数据：等待网络继续送来内容；通道关闭则结束。
                        if (!channel.awaitContent()) break
                        continue
                    }
                    if (uploadedBytes + read > TransferStorage.MAX_FILE_SIZE) {
                        tooLarge = true
                        break
                    }
                    output.write(buffer, 0, read)
                    uploadedBytes += read
                }
                output.flush()
            }

            if (tooLarge) {
                return UploadOutcome.Failed("文件超过 4GB 上限：$safeName", tooLarge = true)
            }

            val target = storage.commitTempFile(tempFile, safeName)
            committed = true
            Log.i(TAG, "接收文件完成：${target.name}（${target.length()} 字节）")
            return UploadOutcome.Saved
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "保存上传文件失败：$safeName", t)
            return UploadOutcome.Failed("保存文件失败：${describe(t)}")
        } finally {
            if (!committed) {
                runCatching { tempFile.delete() }
                    .onFailure { Log.w(TAG, "删除上传临时文件失败", it) }
            }
        }
    }

    private sealed class UploadOutcome {
        data object Saved : UploadOutcome()
        data class Failed(val message: String, val tooLarge: Boolean = false) : UploadOutcome()
    }

    // ---------------------------------------------------------------- 下载与 Range

    /**
     * 发送文件。普通请求返回 200 + 完整内容；合法的单段 Range 返回 206。
     */
    private suspend fun ApplicationCall.respondDownload(file: File) {
        val size = file.length()
        val range = parseRange(request.headers[HttpHeaders.Range], size)
        val contentType = contentTypeFor(file.name)

        response.headers.append(HttpHeaders.AcceptRanges, "bytes")
        response.headers.append(
            HttpHeaders.ContentDisposition,
            "attachment; filename*=UTF-8''${rfc5987Encode(file.name)}",
        )

        when (range) {
            is RangeRequest.Unsatisfiable -> {
                response.headers.append(HttpHeaders.ContentRange, "bytes */$size")
                respondError(HttpStatusCode.RequestedRangeNotSatisfiable, "请求的文件范围无效")
            }

            is RangeRequest.Partial -> {
                val start = range.start
                val end = range.endInclusive
                val length = end - start + 1L
                response.headers.append(
                    HttpHeaders.ContentRange,
                    "bytes $start-$end/$size",
                )
                respondOutputStream(contentType, HttpStatusCode.PartialContent, length) {
                    copyFileRange(file, start, length)
                }
            }

            RangeRequest.Full -> {
                respondOutputStream(contentType, HttpStatusCode.OK, size) {
                    copyFileRange(file, 0L, size)
                }
            }
        }
    }

    /**
     * 只支持单个 bytes 范围：
     * - `bytes=start-end`、`bytes=start-`、`bytes=-suffix` 返回 206；
     * - 多段 Range 或格式无法解析时按完整文件返回（RFC 允许忽略 Range）；
     * - 起点越界/后缀为 0 视为不可满足，返回 416。
     */
    private fun parseRange(header: String?, size: Long): RangeRequest {
        if (header.isNullOrBlank()) return RangeRequest.Full
        if (!header.startsWith("bytes=", ignoreCase = true)) return RangeRequest.Full

        val spec = header.substringAfter('=').trim()
        if (spec.isEmpty() || spec.contains(',')) return RangeRequest.Full

        val dash = spec.indexOf('-')
        if (dash < 0) return RangeRequest.Full
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()

        if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull() ?: return RangeRequest.Full
            if (suffix <= 0L || size <= 0L) return RangeRequest.Unsatisfiable
            val start = (size - suffix).coerceAtLeast(0L)
            return RangeRequest.Partial(start, size - 1L)
        }

        val start = startText.toLongOrNull() ?: return RangeRequest.Full
        if (start < 0L || start >= size) return RangeRequest.Unsatisfiable

        val requestedEnd = if (endText.isEmpty()) {
            size - 1L
        } else {
            endText.toLongOrNull() ?: return RangeRequest.Full
        }
        if (requestedEnd < start) return RangeRequest.Full
        return RangeRequest.Partial(start, requestedEnd.coerceAtMost(size - 1L))
    }

    // 位于 Ktor 响应生产线程，文件 IO 有意阻塞；分块读写避免占用过多内存。
    private fun OutputStream.copyFileRange(file: File, start: Long, length: Long) {
        if (length <= 0L) return
        FileInputStream(file).use { input ->
            try {
                input.channel.position(start)
            } catch (t: Throwable) {
                Log.w(TAG, "定位下载文件失败：${file.name}", t)
                return
            }

            var remaining = length
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (remaining > 0L) {
                val want = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, want)
                if (read < 0) break
                write(buffer, 0, read)
                remaining -= read
            }
            flush()
        }
    }

    private sealed class RangeRequest {
        object Full : RangeRequest()
        data class Partial(val start: Long, val endInclusive: Long) : RangeRequest()
        object Unsatisfiable : RangeRequest()
    }

    // ---------------------------------------------------------------- JSON 与鉴权

    private fun infoJson(): String {
        val version = runCatching {
            @Suppress("DEPRECATION")
            val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            packageInfo.versionName.orEmpty()
        }.getOrDefault("")
        val deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android 设备"

        return buildString {
            append("{\"deviceName\":").append(jsonQuote(deviceName))
            append(",\"freeSpaceBytes\":").append(storage.freeSpaceBytes)
            append(",\"version\":").append(jsonQuote(version))
            append(",\"storagePath\":").append(jsonQuote(storage.rootPath))
            append(",\"maxFileSize\":").append(TransferStorage.MAX_FILE_SIZE)
            append(",\"port\":").append(port)
            append('}')
        }
    }

    /**
     * 文件数组。这里 `path` 使用相对根目录的文件名，
     * 供网页直接拼进 `/api/download/{path}`；App 侧使用的绝对路径在 [TransferFile.path] 中。
     */
    private fun filesJson(): String {
        val files = storage.listFiles()
        return buildString {
            append('[')
            files.forEachIndexed { index, file ->
                if (index > 0) append(',')
                append("{\"name\":").append(jsonQuote(file.name))
                append(",\"path\":").append(jsonQuote(file.name))
                append(",\"size\":").append(file.size)
                append(",\"lastModified\":").append(file.lastModified)
                append(",\"mimeType\":").append(jsonQuote(file.mimeType))
                append('}')
            }
            append(']')
        }
    }

    private fun uploadSuccessJson(count: Int, errors: List<String>): String = buildString {
        append("{\"ok\":true,\"count\":").append(count)
        if (errors.isNotEmpty()) {
            append(",\"errors\":[").append(errors.joinToString(",") { jsonQuote(it) }).append(']')
        }
        append('}')
    }

    private fun jsonQuote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> {
                    if (ch.code < 0x20) {
                        append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }

    private suspend fun ApplicationCall.respondJson(
        payload: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) {
        if (response.isCommitted) {
            Log.w(TAG, "响应已提交，忽略 JSON 响应（$status）")
            return
        }
        try {
            response.headers.append(HttpHeaders.CacheControl, "no-store")
            respondText(payload, JSON_CONTENT_TYPE, status)
        } catch (t: Throwable) {
            Log.w(TAG, "发送 JSON 响应失败", t)
        }
    }

    private suspend fun ApplicationCall.respondError(
        status: HttpStatusCode,
        message: String,
        details: List<String>? = null,
    ) {
        val payload = buildString {
            append("{\"ok\":false,\"error\":").append(jsonQuote(message))
            if (!details.isNullOrEmpty()) {
                append(",\"errors\":[").append(details.joinToString(",") { jsonQuote(it) }).append(']')
            }
            append('}')
        }
        respondJson(payload, status)
    }

    /**
     * 校验 URL 参数 token。页面请求失败返回纯文本，API 请求失败返回 JSON。
     * token 比较使用 [MessageDigest.isEqual]，避免逐字节短路比较。
     */
    private suspend fun requireToken(call: ApplicationCall, jsonError: Boolean): Boolean {
        if (tokenMatches(call.request.queryParameters["t"])) return true

        if (jsonError) {
            call.respondError(HttpStatusCode.Forbidden, "访问令牌无效或已过期")
        } else {
            try {
                call.respondText(
                    "403 访问令牌无效或已过期",
                    ContentType.Text.Plain,
                    HttpStatusCode.Forbidden,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "发送 403 响应失败", t)
            }
        }
        return false
    }

    private fun tokenMatches(provided: String?): Boolean {
        if (provided.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            token.toByteArray(Charsets.UTF_8),
            provided.toByteArray(Charsets.UTF_8),
        )
    }

    private fun contentTypeFor(fileName: String): ContentType =
        runCatching { ContentType.parse(storage.mimeTypeFor(fileName)) }
            .getOrDefault(ContentType.Application.OctetStream)

    private fun rfc5987Encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")

    private fun notifyFilesChanged() {
        runCatching { onFilesChanged() }
            .onFailure { Log.w(TAG, "刷新文件列表回调失败", it) }
    }

    private fun describe(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

    private companion object {
        const val TAG = "TransferServer"
        const val BIND_HOST = "0.0.0.0"
        const val PORT_FIRST = 8080
        const val PORT_LAST = 8099
        val PORT_RANGE = PORT_FIRST..PORT_LAST

        const val INDEX_ASSET_PATH = "transfer/index.html"
        const val UPLOAD_BUFFER_SIZE = 64 * 1024
        const val DOWNLOAD_BUFFER_SIZE = 64 * 1024

        val JSON_CONTENT_TYPE: ContentType =
            ContentType.Application.Json.withCharset(Charsets.UTF_8)
    }
}
