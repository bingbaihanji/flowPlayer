package com.bingbaihanji.flowplayer.core.transfer

/**
 * 传输目录中的一个文件。
 *
 * [path] 为文件的绝对路径，UI 可直接用于打开/分享；
 * 网页 API 返回 JSON 时另行给出相对根目录的路径，避免把手机内部绝对路径拼进下载 URL。
 */
data class TransferFile(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String,
)

/**
 * WiFi 传输服务的对外状态快照。
 *
 * [starting] 表示服务正在启动 Ktor（此时通知已挂出）；[running] 表示 HTTP 服务已经就绪。
 * [token] 是本次启动随机生成的访问令牌，仅在运行中有效，重新启动会更换。
 * [error] 只保存最近一次操作错误，成功启动/删除等操作会清空。
 */
data class TransferState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val address: String = "",
    val port: Int = 0,
    val token: String = "",
    val files: List<TransferFile> = emptyList(),
    val storagePath: String = "",
    val freeSpaceBytes: Long = 0L,
    val error: String? = null,
)
