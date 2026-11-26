package com.bingbaihanji.flowplayer.feature.transfer

import android.app.Application
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
 */
class TransferViewModel(application: Application) : AndroidViewModel(application) {

    private val controller = TransferController(application)

    val state: StateFlow<TransferState> = controller.state

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        connect()
        startServer()
    }

    /** 用户点击“启动服务”：确保仍绑定服务后再启动。 */
    fun start() {
        connect()
        startServer()
    }

    fun stop() {
        runCatching { controller.stopServer() }
            .onFailure { reportFailure("停止接收服务失败", it) }
    }

    fun refresh() {
        runCatching { controller.refreshFiles() }
            .onFailure { reportFailure("刷新接收列表失败", it) }
    }

    fun delete(file: TransferFile) {
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
    }
}
