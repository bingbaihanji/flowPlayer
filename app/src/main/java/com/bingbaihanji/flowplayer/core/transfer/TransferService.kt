package com.bingbaihanji.flowplayer.core.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.bingbaihanji.flowplayer.MainActivity
import com.bingbaihanji.flowplayer.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.security.SecureRandom

/**
 * WiFi 传输前台服务。
 *
 * 和 [com.bingbaihanji.flowplayer.core.player.vlc.VlcPlaybackService] 一样是普通 Service +
 * LocalBinder：控制器与服务同进程，直接镜像 [state]。
 *
 * 生命周期要点：
 * - onCreate/onStartCommand 第一步就是 startForeground（通知 id 1003），然后再做任何磁盘/Ktor 操作；
 * - startServer 在 IO 线程启动 Ktor，成功后发布 running/address/port/token；
 * - stopServer 释放 Ktor、WifiLock、WakeLock 与通知并 stopSelf；
 * - 启动失败（无 Wi-Fi / 端口不可用）会保留 error 状态供 UI 展示，但会退出前台并 stopSelf，
 *   避免一个不工作的前台服务长期驻留；控制器仍绑定时服务对象与状态继续可用，可再次启动；
 * - onTaskRemoved 在未运行时清理并 stopSelf，运行中则继续传输；
 * - onDestroy 兜底释放全部资源，可被重新启动。
 */
class TransferService : Service() {

    private val binder = LocalBinder()

    /** 服务侧唯一状态，控制器只读镜像。 */
    private val _state = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 串行化 start/stop，避免快速“停止→启动”时状态与通知互相覆盖。 */
    private val lifecycleMutex = Mutex()

    /** 保护 [server] 赋值与 onDestroy 清理的竞态。 */
    private val serverLock = Any()

    private val secureRandom = SecureRandom()

    private lateinit var storage: TransferStorage

    @Volatile
    private var server: TransferServer? = null

    @Volatile
    private var destroyed = false

    @Volatile
    private var foregroundStarted = false

    /** 序列化通知的创建/刷新/移除，避免停止后后台刷新又重新建出普通通知。 */
    private val notificationLock = Any()

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ---------------------------------------------------------------- 生命周期

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 必须最先进入前台：startForegroundService 只给 5 秒窗口。
        ensureForeground()
        storage = TransferStorage(this)
        _state.update {
            it.copy(
                storagePath = storage.rootPath,
                freeSpaceBytes = storage.freeSpaceBytes,
            )
        }
        // 先把已有文件读出来，UI 进入页面时即使服务未启动也能看到接收列表。
        refreshFiles()
        Log.d(TAG, "传输服务已创建，目录：${storage.rootPath}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 再次保证前台状态，然后再处理启动/停止动作。
        ensureForeground()
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        ensureForeground()
        return binder
    }

    /**
     * 用户从最近任务划掉应用：未运行则清理并结束服务；运行中保持后台传输。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!_state.value.running) {
            stopServer()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        destroyed = true
        serviceScope.cancel()

        val currentServer = synchronized(serverLock) {
            server.also { server = null }
        }
        if (currentServer != null) {
            try {
                currentServer.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "销毁时停止传输服务失败", t)
            }
        }

        releaseLocks()
        removeForegroundNotification()
        _state.update {
            it.copy(
                running = false,
                starting = false,
                address = "",
                port = 0,
                token = "",
            )
        }
        Log.d(TAG, "传输服务已销毁")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 对控制器暴露的操作

    /**
     * 启动 HTTP 服务。已运行时重复调用会被忽略。
     */
    fun startServer() {
        enqueueLifecycle { startServerInternal() }
    }

    /**
     * 停止 HTTP 服务，释放全部锁与通知并 stopSelf。绑定的控制器仍能读取停止后的状态。
     */
    fun stopServer() {
        enqueueLifecycle { stopServerInternal() }
    }

    /**
     * 重新读取传输目录与剩余空间，并刷新 UI/通知。可在任意线程调用。
     */
    fun refreshFiles() {
        if (destroyed) return
        serviceScope.launch { refreshFilesInternal() }
    }

    /**
     * 删除 App 列表中的文件；只允许传输根目录内的普通文件。
     */
    fun deleteFile(file: TransferFile) {
        if (destroyed) return
        serviceScope.launch {
            val deleted = storage.deleteManagedFile(file)
            if (deleted) {
                _state.update { it.copy(error = null) }
            } else {
                _state.update { it.copy(error = "删除失败：${file.name}") }
            }
            refreshFilesInternal()
        }
    }

    private fun enqueueLifecycle(block: suspend () -> Unit) {
        if (destroyed) return
        serviceScope.launch {
            lifecycleMutex.withLock {
                if (destroyed) return@withLock
                block()
            }
        }
    }

    // ---------------------------------------------------------------- 启动 / 停止

    private suspend fun startServerInternal() {
        if (_state.value.running || _state.value.starting) return

        _state.update { it.copy(starting = true, running = false, error = null) }
        // startForeground 属于服务生命周期调用，统一回到主线程执行。
        withContext(Dispatchers.Main.immediate) { ensureForeground() }
        refreshNotification()

        // 没有局域网 IPv4 时启动也毫无意义，直接给出明确提示。
        val address = resolveLanIpv4()
        if (address == null) {
            failStart("请先连接 Wi-Fi")
            return
        }

        acquireLocks()
        val token = generateToken()
        val transferServer = TransferServer(this, storage, token) { refreshFiles() }

        try {
            val actualPort = transferServer.start()
            val accepted = synchronized(serverLock) {
                if (destroyed) {
                    false
                } else {
                    server = transferServer
                    true
                }
            }
            if (!accepted) {
                transferServer.stop()
                releaseLocks()
                return
            }

            val files = storage.listFiles()
            _state.update {
                it.copy(
                    running = true,
                    starting = false,
                    address = address,
                    port = actualPort,
                    token = token,
                    files = files,
                    storagePath = storage.rootPath,
                    freeSpaceBytes = storage.freeSpaceBytes,
                    error = null,
                )
            }
            refreshNotification()
            Log.i(TAG, "传输服务启动成功：http://$address:$actualPort/")
        } catch (e: CancellationException) {
            transferServer.stop()
            releaseLocks()
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "启动传输服务失败", t)
            transferServer.stop()
            releaseLocks()
            failStart("启动传输服务失败：${describeThrowable(t)}")
        }
    }

    private suspend fun stopServerInternal() {
        _state.update {
            it.copy(
                running = false,
                starting = false,
                address = "",
                port = 0,
                token = "",
                error = null,
            )
        }

        val currentServer = synchronized(serverLock) {
            server.also { server = null }
        }
        if (currentServer != null) {
            try {
                currentServer.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "停止传输服务失败", t)
            }
        }

        releaseLocks()
        withContext(Dispatchers.Main.immediate) {
            removeForegroundNotification()
            stopSelf()
        }
    }

    /**
     * 启动失败时保留 error 给 UI，同时退出前台并结束服务。
     * 若控制器仍绑定，服务对象会存活到解绑，期间可直接重试 startServer。
     */
    private suspend fun failStart(message: String) {
        _state.update {
            it.copy(
                running = false,
                starting = false,
                address = "",
                port = 0,
                token = "",
                storagePath = storage.rootPath,
                freeSpaceBytes = storage.freeSpaceBytes,
                error = message,
            )
        }
        releaseLocks()
        refreshNotification()
        withContext(Dispatchers.Main.immediate) {
            removeForegroundNotification()
            stopSelf()
        }
    }

    private fun refreshFilesInternal() {
        if (destroyed) return
        val files = storage.listFiles()
        val freeSpace = storage.freeSpaceBytes
        _state.update {
            it.copy(
                files = files,
                freeSpaceBytes = freeSpace,
                storagePath = storage.rootPath,
            )
        }
        refreshNotification()
    }

    // ---------------------------------------------------------------- 网络地址 / 令牌

    /**
     * 取活跃网络 LinkProperties 中的非 loopback、非 link-local IPv4。
     *
     * 只接受 Wi-Fi / 以太网传输类型；蜂窝网络或 VPN 下拿到的地址 PC 无法访问。
     * 若活跃网络不是局域网（例如开着蜂窝数据/VPN），再遍历全部网络找已连接的 Wi-Fi，
     * 保证“Wi-Fi 有 IP 但非默认网络”时仍能正常传输。
     */
    @Suppress("DEPRECATION")
    private fun resolveLanIpv4(): String? {
        return try {
            val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return null

            val activeIpv4 = connectivityManager.activeNetwork
                ?.let { network -> lanIpv4Of(connectivityManager, network) }
            if (activeIpv4 != null) return activeIpv4

            connectivityManager.allNetworks
                .asSequence()
                .mapNotNull { network -> lanIpv4Of(connectivityManager, network) }
                .firstOrNull()
        } catch (t: Throwable) {
            Log.w(TAG, "读取局域网 IP 失败", t)
            null
        }
    }

    private fun lanIpv4Of(connectivityManager: ConnectivityManager, network: android.net.Network): String? {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        val isLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!isLan) return null

        val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
        return linkProperties.linkAddresses
            .asSequence()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }

    /** 每次启动生成新的 URL-safe 随机 token（无填充，可直接放进 URL query）。 */
    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    // ---------------------------------------------------------------- 锁

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wifiLock == null) {
            try {
                val manager = getSystemService(WifiManager::class.java)
                val lock = manager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$LOCK_TAG:wifi")
                if (lock != null) {
                    lock.setReferenceCounted(false)
                    lock.acquire()
                    wifiLock = lock
                }
            } catch (t: Throwable) {
                Log.w(TAG, "获取 WifiLock 失败", t)
            }
        }
        if (wakeLock == null) {
            try {
                val manager = getSystemService(PowerManager::class.java)
                val lock = manager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$LOCK_TAG:transfer")
                if (lock != null) {
                    lock.setReferenceCounted(false)
                    lock.acquire()
                    wakeLock = lock
                }
            } catch (t: Throwable) {
                Log.w(TAG, "获取 WakeLock 失败", t)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseLocks() {
        val wifi = wifiLock
        wifiLock = null
        if (wifi != null) {
            try {
                if (wifi.isHeld) wifi.release()
            } catch (t: Throwable) {
                Log.w(TAG, "释放 WifiLock 失败", t)
            }
        }
        val wake = wakeLock
        wakeLock = null
        if (wake != null) {
            try {
                if (wake.isHeld) wake.release()
            } catch (t: Throwable) {
                Log.w(TAG, "释放 WakeLock 失败", t)
            }
        }
    }

    // ---------------------------------------------------------------- 通知

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WiFi 文件传输",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "局域网内上传、下载手机文件"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun ensureForeground() {
        synchronized(notificationLock) {
            if (foregroundStarted) {
                notifyNotificationLocked()
                return
            }
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(),
                    foregroundServiceType(),
                )
                foregroundStarted = true
            } catch (t: Throwable) {
                Log.e(TAG, "进入前台服务失败", t)
                _state.update { it.copy(error = "无法创建前台通知：${describeThrowable(t)}") }
            }
        }
    }

    private fun refreshNotification() {
        synchronized(notificationLock) {
            if (foregroundStarted) notifyNotificationLocked()
        }
    }

    private fun notifyNotificationLocked() {
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "刷新传输通知失败", t)
        }
    }

    private fun removeForegroundNotification() {
        synchronized(notificationLock) {
            if (foregroundStarted) {
                try {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                } catch (t: Throwable) {
                    Log.w(TAG, "移除前台通知失败", t)
                }
                foregroundStarted = false
            }
            try {
                getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            } catch (t: Throwable) {
                Log.w(TAG, "取消传输通知失败", t)
            }
        }
    }

    private fun buildNotification(): Notification {
        val current = _state.value
        val statusText = when {
            current.error != null -> current.error
            current.starting -> "正在启动…"
            current.running -> "http://${current.address}:${current.port}/ · ${current.files.size} 个文件"
            else -> "服务未运行"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_flowplayer)
            .setContentTitle("WiFi 文件传输")
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setContentIntent(mainActivityPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "停止",
                    stopPendingIntent(),
                ),
            )
            .build()
    }

    private fun mainActivityPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun stopPendingIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, TransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

    private fun describeThrowable(t: Throwable): String = when (t) {
        is SecurityException -> "没有存储或网络权限"
        is IOException -> t.message?.takeIf { it.isNotBlank() } ?: "磁盘读写失败"
        else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
    }

    /**
     * 本地绑定对象。控制器拿到 service 后直接调用公开方法并镜像 [state]。
     */
    inner class LocalBinder : Binder() {
        val service: TransferService
            get() = this@TransferService
    }

    companion object {
        private const val TAG = "TransferService"

        const val ACTION_START = "com.bingbaihanji.flowplayer.transfer.action.START"
        const val ACTION_STOP = "com.bingbaihanji.flowplayer.transfer.action.STOP"

        /** 通知渠道与 id 由 M4 计划固定，便于验收与排障。 */
        private const val CHANNEL_ID = "flowplayer_transfer"
        const val NOTIFICATION_ID = 1003

        private const val LOCK_TAG = "FlowPlayer:transfer"
        private const val TOKEN_BYTES = 24

        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_STOP = 1
    }
}
