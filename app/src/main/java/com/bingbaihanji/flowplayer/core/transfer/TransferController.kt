package com.bingbaihanji.flowplayer.core.transfer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * WiFi 传输控制客户端。
 *
 * 与 `VlcPlayerController` 相同的模式：同进程 LocalBinder 直接拿到 [TransferService]，
 * 把服务端 [TransferService.state] 镜像到本控制器的 [state]；UI 只依赖 [TransferState]。
 *
 * - [connect] 只绑定服务（服务 onCreate 会先进入前台并读取文件列表）；
 * - [startServer] 使用 `startForegroundService` 请求服务启动 Ktor，再确保绑定；
 * - [release] 只解绑，不替用户停止服务器。
 */
class TransferController(context: Context) {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private var service: TransferService? = null
    private var connecting = false
    private var bindRequested = false
    private var released = false

    /** 绑定完成后是否需要补发一次 startServer（startForegroundService 与本控制器绑定存在时序差）。 */
    private var pendingStart = false

    private var stateJob: Job? = null

    private val connection: ServiceConnection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            connecting = false
            if (released) return

            val localBinder = binder as? TransferService.LocalBinder
            if (localBinder == null) {
                Log.w(TAG, "传输服务返回了未知 Binder")
                _state.update { it.copy(error = "无法连接传输服务") }
                return
            }

            val svc = localBinder.service
            service = svc

            // 先镜像服务状态（立即收到当前快照），再补发等待中的启动请求。
            stateJob?.cancel()
            stateJob = scope.launch {
                svc.state.collect { latest -> _state.value = latest }
            }

            if (pendingStart) {
                pendingStart = false
                svc.startServer()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connecting = false
            service = null
            stateJob?.cancel()
            stateJob = null
            // 服务进程异常退出时解除旧绑定，允许后续 connect() 重新绑定。
            if (bindRequested) {
                runCatching { appContext.unbindService(connection) }
                    .onFailure { Log.w(TAG, "解除传输服务绑定失败", it) }
                bindRequested = false
            }
            _state.update {
                it.copy(
                    running = false,
                    starting = false,
                    address = "",
                    port = 0,
                    token = "",
                    error = "传输服务已断开",
                )
            }
        }
    }

    // ---------------------------------------------------------------- 连接管理

    /**
     * 绑定传输服务；幂等。仅绑定不会启动 HTTP 服务，服务 onCreate 会进入前台并读取列表。
     */
    fun connect() {
        if (released || connecting || service != null) return
        bindIfNeeded()
    }

    private fun bindIfNeeded() {
        if (released || bindRequested || connecting || service != null) return
        connecting = true
        val intent = Intent(appContext, TransferService::class.java)
        val bound = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (bound) {
            bindRequested = true
        } else {
            connecting = false
            pendingStart = false
            Log.w(TAG, "绑定传输服务失败")
            _state.update { it.copy(starting = false, error = "无法连接传输服务") }
        }
    }

    /**
     * 断开并释放控制器；服务器是否继续由 [stopServer] 决定，释放后控制器不可复用。
     */
    fun release() {
        if (released) return
        released = true
        pendingStart = false
        stateJob?.cancel()
        stateJob = null
        service = null

        if (bindRequested) {
            runCatching { appContext.unbindService(connection) }
                .onFailure { Log.w(TAG, "解绑传输服务失败", it) }
            bindRequested = false
        }
        scope.cancel()
        _state.update { it.copy(starting = false) }
    }

    // ---------------------------------------------------------------- 服务操作

    /**
     * 启动 HTTP 服务。
     *
     * 已绑定时也发送 startForegroundService 请求，让服务处于“已启动”状态，
     * 这样 UI 解绑后服务器仍可继续后台传输（否则仅 bound 的服务在解绑时会被销毁）。
     */
    fun startServer() {
        if (released) return
        // 已运行时不重复启动，否则服务端会直接忽略而控制器会停留在 starting 状态。
        if (_state.value.running) return
        _state.update { it.copy(starting = true, error = null) }

        val intent = Intent(appContext, TransferService::class.java)
            .setAction(TransferService.ACTION_START)
        val foregroundRequested = runCatching {
            ContextCompat.startForegroundService(appContext, intent)
        }.onFailure {
            Log.w(TAG, "启动传输前台服务失败", it)
        }.isSuccess

        val svc = service
        if (svc != null) {
            // startForegroundService 失败时仍可通过已持有的 Binder 启动；
            // 成功时 onStartCommand 会触发启动，这里不重复调用以免排队。
            if (!foregroundRequested) svc.startServer()
            return
        }

        pendingStart = true
        bindIfNeeded()
        if (!foregroundRequested && !bindRequested && !connecting) {
            pendingStart = false
            _state.update {
                it.copy(starting = false, error = "启动传输服务失败，请稍后重试")
            }
        }
    }

    /**
     * 停止 HTTP 服务。若尚未绑定，则向已启动的服务发送停止指令。
     */
    fun stopServer() {
        if (released) return
        pendingStart = false
        _state.update {
            it.copy(
                running = false,
                starting = false,
                address = "",
                port = 0,
                token = "",
            )
        }

        val svc = service
        if (svc != null) {
            svc.stopServer()
            return
        }

        runCatching {
            appContext.startService(
                Intent(appContext, TransferService::class.java)
                    .setAction(TransferService.ACTION_STOP),
            )
        }.onFailure { Log.w(TAG, "发送停止传输服务请求失败", it) }
    }

    /**
     * 刷新文件列表与剩余空间。未绑定时顺带发起绑定。
     */
    fun refreshFiles() {
        if (released) return
        val svc = service
        if (svc == null) {
            bindIfNeeded()
            return
        }
        svc.refreshFiles()
    }

    /**
     * 删除传输目录中的文件。文件必须在服务根目录内，否则服务会拒绝并给出错误状态。
     */
    fun deleteFile(file: TransferFile) {
        if (released) return
        val svc = service
        if (svc == null) {
            _state.update { it.copy(error = "传输服务未连接") }
            bindIfNeeded()
            return
        }
        svc.deleteFile(file)
    }

    private companion object {
        const val TAG = "TransferController"
    }
}
