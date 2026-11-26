package com.bingbaihanji.flowplayer.core.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import com.bingbaihanji.flowplayer.core.data.settings.SettingsRepository
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
 * - 启动失败（无局域网 / 端口不可用）会保留 error 状态供 UI 展示，但会退出前台并 stopSelf，
 *   避免一个不工作的前台服务长期驻留；控制器仍绑定时服务对象与状态继续可用，可再次启动；
 * - 运行期间监听 Wi-Fi / 以太网地址变化，断网只更新提示，服务器保持运行等待重连；
 * - Android 15+ 的 dataSync 前台服务 6 小时时限由 onTimeout 快速停止前台与服务，避免系统强杀；
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

    /** 设置仓库：启动服务时读取/生成持久化传输 token。 */
    private lateinit var settingsRepository: SettingsRepository

    @Volatile
    private var server: TransferServer? = null

    @Volatile
    private var destroyed = false

    @Volatile
    private var foregroundStarted = false

    /** 序列化通知的创建/刷新/移除，避免停止后后台刷新又重新建出普通通知。 */
    private val notificationLock = Any()

    /** 运行期监听的局域网网络回调；启动成功后注册，停止/超时/销毁时注销。 */
    private var lanNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /** 保护 [lanNetworkCallback] 的注册与注销。 */
    private val networkLock = Any()

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ---------------------------------------------------------------- 生命周期

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 必须最先进入前台：startForegroundService 只给 5 秒窗口。
        ensureForeground()
        storage = TransferStorage(this)
        settingsRepository = SettingsRepository.from(this)
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

    /**
     * Android 15（API 35）起 dataSync 前台服务单次最长运行约 6 小时，超时由系统回调此方法。
     * 必须在回调内尽快停止前台与服务，否则会触发 ForegroundServiceDidNotStopInTimeException。
     * 按平台约定不调用 super，避免父类的默认实现参与处理。
     */
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "前台服务超时（startId=$startId），停止传输")
        handleForegroundTimeout()
    }

    /**
     * Android 15 起系统也会按前台服务类型回调 onTimeout(startId, fgsType)；
     * 与 [onTimeout] 共用同一套快速停止逻辑，不调用 super。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "前台服务超时（startId=$startId, type=$fgsType），停止传输")
        handleForegroundTimeout()
    }

    override fun onDestroy() {
        destroyed = true
        unregisterLanNetworkCallback()
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
        if (!foregroundStarted) {
            // 没有前台通知就不应继续跑 HTTP 服务，否则会被系统按后台服务清理。
            failStart("无法创建前台通知，传输服务未启动")
            return
        }
        refreshNotification()

        // 没有局域网 IPv4 时启动也毫无意义，直接给出明确提示。
        val address = resolveLanIpv4()
        if (address == null) {
            failStart("请先连接 Wi-Fi 或以太网")
            return
        }

        // 优先复用持久化 token（重启后 PC 端保存的地址仍可用）；DataStore 读写失败时回退到临时 token。
        // 当前在 serviceScope（Dispatchers.IO）中执行，挂起的 DataStore 操作不会阻塞主线程。
        // 放在 acquireLocks 之前，避免挂起点跨过锁的获取/释放。
        val token = try {
            settingsRepository.ensureTransferToken().ifBlank { generateToken() }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Exception) {
            Log.w(TAG, "读取持久化传输令牌失败，回退到临时令牌", t)
            generateToken()
        }

        acquireLocks()
        val transferServer = try {
            TransferServer(this, storage, token) { refreshFiles() }
        } catch (t: Throwable) {
            // 兜底 Ktor 类在低版本 Android 上加载失败等异常，转成 UI 可见错误而不是崩溃。
            Log.w(TAG, "创建传输服务器失败", t)
            releaseLocks()
            failStart("创建传输服务器失败：${describeThrowable(t)}")
            return
        }

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
            // 服务启动成功后再监听局域网变化：保持服务器运行，只随地址变化刷新状态与通知。
            registerLanNetworkCallback()
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

        unregisterLanNetworkCallback()

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
        unregisterLanNetworkCallback()
        releaseLocks()
        refreshNotification()
        withContext(Dispatchers.Main.immediate) {
            removeForegroundNotification()
            stopSelf()
        }
    }

    /**
     * 系统前台服务时限到达后的快速收尾。
     *
     * 先撤下前台通知并 stopSelf，再释放 Ktor/WifiLock/WakeLock，确保系统判定服务已停止；
     * 错误信息保留在 state 中，控制器仍绑定时 UI 可以直接看到停止原因并可重新启动。
     */
    private fun handleForegroundTimeout() {
        if (destroyed) return
        unregisterLanNetworkCallback()
        _state.update {
            it.copy(
                running = false,
                starting = false,
                address = "",
                port = 0,
                token = "",
                error = FOREGROUND_TIMEOUT_MESSAGE,
            )
        }

        // 优先撤下前台并请求停止，避免超过系统宽限期。
        removeForegroundNotification()
        stopSelf()

        val currentServer = synchronized(serverLock) {
            server.also { server = null }
        }
        if (currentServer != null) {
            try {
                currentServer.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "超时停止传输服务失败", t)
            }
        }
        releaseLocks()
        Log.w(TAG, "传输前台服务已因系统运行时限停止")
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

    private fun lanIpv4Of(connectivityManager: ConnectivityManager, network: Network): String? {
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

    /**
     * 服务运行期间监听 Wi-Fi / 以太网连接变化。
     *
     * 同一个 NetworkRequest 同时声明 Wi-Fi 与以太网（多个 transport 之间为“或”关系），
     * 即使局域网网络不是默认网络也能收到回调，不会因蜂窝/VPN 抢占默认网络而漏掉地址变化。
     * 注册失败只记录日志，不影响已经启动的服务器。
     */
    private fun registerLanNetworkCallback() {
        if (destroyed) return
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return
        synchronized(networkLock) {
            if (destroyed || lanNetworkCallback != null) return@synchronized
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshLanAddress()
                }

                override fun onLost(network: Network) {
                    refreshLanAddress()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    refreshLanAddress()
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) {
                    // DHCP 续租、静态地址切换等 IP 变化会走这里，需要重新解析地址。
                    refreshLanAddress()
                }
            }
            try {
                connectivityManager.registerNetworkCallback(lanNetworkRequest(), callback)
                lanNetworkCallback = callback
            } catch (t: Throwable) {
                Log.w(TAG, "注册局域网网络回调失败", t)
            }
        }
    }

    /** 注销运行期网络回调；可重复调用。 */
    private fun unregisterLanNetworkCallback() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        synchronized(networkLock) {
            val callback = lanNetworkCallback ?: return@synchronized
            lanNetworkCallback = null
            if (connectivityManager == null) return@synchronized
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                .onFailure { Log.w(TAG, "注销局域网网络回调失败", it) }
        }
    }

    /** 同时匹配 Wi-Fi 或以太网网络的请求，不要求它是系统默认网络。 */
    private fun lanNetworkRequest(): NetworkRequest =
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

    /** 网络回调线程不直接解析网络，切到 IO 线程后按当前网络状态重新计算地址。 */
    private fun refreshLanAddress() {
        if (destroyed) return
        serviceScope.launch { applyCurrentLanAddress() }
    }

    /**
     * 地址变化时只刷新 [state] 与通知，不停止服务器：
     * - 断网时保留 running，地址清空并给出断开提示，等待重新连接；
     * - 恢复/切换网络后写回新地址并清掉断开提示。
     */
    private fun applyCurrentLanAddress() {
        if (destroyed) return
        val current = _state.value
        if (!current.running) return

        val address = resolveLanIpv4()
        if (address == null) {
            if (current.address.isNotBlank()) {
                _state.update { state ->
                    // 解析期间用户可能已经停止服务，此时不能再把断开提示写回状态。
                    if (!state.running || state.address.isBlank()) {
                        state
                    } else {
                        state.copy(address = "", error = LAN_DISCONNECTED_MESSAGE)
                    }
                }
                refreshNotification()
                Log.w(TAG, "局域网连接已断开，传输服务保持运行等待重连")
            }
            return
        }

        val wasDisconnected = current.error == LAN_DISCONNECTED_MESSAGE
        if (address == current.address && !wasDisconnected) return

        _state.update { state ->
            if (!state.running) {
                state
            } else {
                state.copy(
                    address = address,
                    error = if (state.error == LAN_DISCONNECTED_MESSAGE) null else state.error,
                )
            }
        }
        refreshNotification()
        Log.i(TAG, "局域网地址已更新：http://$address:${current.port}/")
    }

    /**
     * 生成 URL-safe 随机 token（无填充，可直接放进 URL query）。
     *
     * 这是 M4 的临时 token 逻辑，仅在持久化 token 读取失败时作为回退使用。
     */
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
            // 运行中但地址被清空说明局域网已断开，避免拼出 "http://:port" 这样的无效地址。
            current.running && current.address.isNotBlank() ->
                "http://${current.address}:${current.port}/ · ${current.files.size} 个文件"
            current.running -> "网络已断开，等待重新连接"
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

        private const val LAN_DISCONNECTED_MESSAGE = "网络连接已断开，请重新连接 Wi-Fi 或以太网"
        private const val FOREGROUND_TIMEOUT_MESSAGE =
            "前台服务已达到系统运行时限（约 6 小时），传输已停止，请重新启动"

        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_STOP = 1
    }
}
