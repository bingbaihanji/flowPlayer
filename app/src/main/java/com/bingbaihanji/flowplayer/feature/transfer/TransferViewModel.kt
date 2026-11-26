package com.bingbaihanji.flowplayer.feature.transfer

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.bingbaihanji.flowplayer.core.transfer.TransferController
import com.bingbaihanji.flowplayer.core.transfer.TransferFile
import com.bingbaihanji.flowplayer.core.transfer.TransferState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 接收文件页面的 ViewModel。
 *
 * 只依赖 core/transfer 暴露的 [TransferController] 契约，不直接访问传输服务实现。
 * 页面首次创建时就连接服务并启动接收端；离开页面只解绑（服务是否继续由服务自身的停止操作决定）。
 *
 * Ktor CIO 依赖 java.nio.file（Android 8.0/API 26 起才提供），因此低于 O 的系统不连接、
 * 不启动内嵌服务器，只在页面上显示不支持提示，避免在 API 24/25 上加载 Ktor 失败。
 */
class TransferViewModel(application: Application) : AndroidViewModel(application) {

    private val controller = TransferController(application)

    val state: StateFlow<TransferState> = controller.state

    /** 当前系统是否支持内嵌传输（Ktor 需要 Android 8.0+）。 */
    val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        if (supported) {
            connect()
            startServer()
        } else {
            Log.w(TAG, "当前系统版本低于 Android 8.0，跳过内嵌传输服务启动")
        }
    }

    /** 用户点击“启动服务”：确保仍绑定服务后再启动。不支持的系统直接忽略。 */
    fun start() {
        if (!supported) {
            _messages.trySend(UNSUPPORTED_MESSAGE)
            return
        }
        connect()
        startServer()
    }

    fun stop() {
        if (!supported) return
        runCatching { controller.stopServer() }
            .onFailure { reportFailure("停止接收服务失败", it) }
    }

    fun refresh() {
        // 低版本系统不触碰传输服务，避免无意义地绑定服务或启动前台通知。
        if (!supported) return
        runCatching { controller.refreshFiles() }
            .onFailure { reportFailure("刷新接收列表失败", it) }
    }

    fun delete(file: TransferFile) {
        if (!supported) return
        runCatching { controller.deleteFile(file) }
            .onFailure { reportFailure("删除文件失败", it) }
    }

    override fun onCleared() {
        // 服务生命周期由停止操作决定，页面销毁只解除绑定，避免后台传输被意外中断。
        runCatching { controller.release() }
            .onFailure { Log.w(TAG, "解绑接收服务失败", it) }
        super.onCleared()
    }

    private fun connect() {
        runCatching { controller.connect() }
            .onFailure { reportFailure("连接接收服务失败", it) }
    }

    private fun startServer() {
        runCatching { controller.startServer() }
            .onFailure { reportFailure("启动接收服务失败", it) }
    }

    private fun reportFailure(message: String, throwable: Throwable) {
        Log.w(TAG, message, throwable)
        _messages.trySend("$message：${throwable.message ?: "未知错误"}")
    }

    private companion object {
        const val TAG = "TransferViewModel"

        /** 与页面上的不支持提示保持一致。 */
        const val UNSUPPORTED_MESSAGE = "当前系统版本不支持内嵌传输（需 Android 8.0+）"
    }
}
