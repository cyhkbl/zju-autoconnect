package cn.cyhkbl.zjuautoconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 前台服务:监听 WiFi 状态,连上 ZJUWLAN 后自动登录
 *
 * 为什么用前台服务:
 *  - Android 8+ 后台服务无法长期存活,前台服务带通知可保活
 *  - 用户首次启动会看到常驻通知,可手动停止
 *
 * 触发逻辑:
 *  - 注册 NetworkCallback 监听所有 WiFi 网络
 *  - 连上 WiFi 后检查 SSID,匹配目标列表(ZJUWLAN) → 触发登录
 *  - 用 AtomicBoolean 防止并发登录
 */
class NetworkMonitorService : Service() {

    companion object {
        private const val TAG = "NetMonService"
        private const val CHANNEL_ID = "zju_autoconnect_status"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "cn.cyhkbl.zjuautoconnect.START"
        private const val ACTION_STOP = "cn.cyhkbl.zjuautoconnect.STOP"

        // 浙大校园网 SSID — 触发自动登录的目标
        private val TARGET_SSIDS = setOf("ZJUWLAN", "ZJUWLAN-Secure", "ZJUWLAN-AUTO")

        fun start(context: Context) {
            val intent = Intent(context, NetworkMonitorService::class.java)
                .setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, NetworkMonitorService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    // 全局状态(供 UI 读取)
    object State {
        val isRunning = AtomicBoolean(false)
        val lastSsid = AtomicReference<String?>(null)
        val lastLoginAt = AtomicReference<Long?>(null)
        val lastLoginResult = AtomicReference<SrunLogin.Result?>(null)
        val totalAttempts = AtomicInteger(0)
        val successfulLogins = AtomicInteger(0)
        // 最近日志快照(供 Activity 读取)
        val logSnapshot = AtomicReference<List<String>>(emptyList())
    }

    // 日志环形缓冲(最近 50 条)
    private val logBuffer = ArrayDeque<String>()
    private val logLock = Any()
    private val maxLogLines = 50

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wifiManager: WifiManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isLoginInFlight = AtomicBoolean(false)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // onAvailable 触发时 SSID 经常还没就绪(尤其 Android 12+),
            // 立即尝试一次,失败就等 onCapabilitiesChanged
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                handleWifiCapabilities(network, caps)
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                handleWifiCapabilities(network, caps)
            }
        }

        override fun onLost(network: Network) {
            log("WiFi 断开")
            State.lastSsid.set(null)
            State.lastLoginResult.set(null)
            updateStatusNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        createNotificationChannel()
        State.isRunning.set(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildStatusNotification("监听中…", "等待连接 ZJUWLAN"))
        registerNetworkCallback()
        // 启动时立即查一次当前 WiFi(可能服务启动前已经连上 ZJUWLAN)
        serviceScope.launch { checkCurrentWifiOnce() }
        return START_STICKY
    }

    /**
     * 服务启动时主动检查:用当前 active network + caps 查一次
     * (callback 可能要等 WiFi 重连才触发)
     */
    private suspend fun checkCurrentWifiOnce() {
        val active = connectivityManager.activeNetwork ?: run {
            log("当前无活动网络,等待 WiFi 回调")
            return
        }
        val caps = connectivityManager.getNetworkCapabilities(active) ?: return
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            handleWifiCapabilities(active, caps)
        } else {
            log("当前非 WiFi,等待 WiFi 回调")
        }
    }

    private fun registerNetworkCallback() {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivityManager.registerNetworkCallback(req, networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "注册 NetworkCallback 失败", e)
        }
    }

    /**
     * WiFi capabilities 就绪时的处理:
     * 1. 尝试读取 SSID
     * 2. SSID 匹配目标 → 触发登录
     * 3. SSID 读不到 → 明确打日志(用户能看出是权限问题)
     */
    private fun handleWifiCapabilities(network: Network, caps: NetworkCapabilities) {
        val ssid = readSsidFromCaps(caps)
        if (ssid == null) {
            val reason = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    "Android 12+ 读取 SSID 需要位置权限(设置→应用→ZJU 校园网→位置)"
                else -> "transportInfo 为空"
            }
            log("⚠️ WiFi 已连接但 SSID 不可见: $reason")
            State.lastSsid.set(null)
            updateStatusNotification()
            return
        }
        // 同一 SSID 多次触发不重复登录
        if (ssid == State.lastSsid.get()) {
            return
        }
        State.lastSsid.set(ssid)
        log("📶 已连接 WiFi: $ssid")
        updateStatusNotification()
        if (ssid in TARGET_SSIDS) {
            log("✅ 匹配目标 → 触发登录")
            serviceScope.launch { attemptLogin() }
        } else {
            log("⏭ 非目标 SSID (目标: $TARGET_SSIDS),跳过登录")
        }
    }

    /**
     * 从 NetworkCapabilities 读 SSID(API 31+ 唯一可靠方式)
     * API < 31 走 wifiManager.connectionInfo 兜底
     */
    private fun readSsidFromCaps(caps: NetworkCapabilities): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val transportInfo = caps.transportInfo as? WifiInfo
            val raw = transportInfo?.ssid ?: return null
            return sanitizeSsid(raw)
        }
        @Suppress("DEPRECATION")
        val info = wifiManager.connectionInfo ?: return null
        val raw = info.ssid ?: return null
        return sanitizeSsid(raw)
    }

    private fun sanitizeSsid(raw: String): String? {
        if (raw.isBlank() || raw == "<unknown ssid>") return null
        // 7.0+ WifiInfo 返回带引号的 SSID
        return raw.trim('"').takeIf { it.isNotBlank() }
    }

    private suspend fun attemptLogin() {
        if (!isLoginInFlight.compareAndSet(false, true)) {
            log("已有登录任务在跑,跳过")
            return
        }
        try {
            val (username, password) = PrefsManager.getCredentials(this)
            if (username.isBlank() || password.isBlank()) {
                log("未配置账号密码,请在 App 里填写")
                return
            }
            State.totalAttempts.incrementAndGet()
            val start = System.currentTimeMillis()
            State.lastLoginAt.set(start)
            log("开始登录 (user=${username.takeLast(4).padStart(username.length, '*')})")
            updateStatusNotification()
            val result = SrunLogin.login(username, password)
            val cost = System.currentTimeMillis() - start
            State.lastLoginResult.set(result)
            when (result) {
                is SrunLogin.Result.Success -> {
                    State.successfulLogins.incrementAndGet()
                    log("✅ 登录成功 (${cost}ms)")
                }
                is SrunLogin.Result.AlreadyOnline -> {
                    State.successfulLogins.incrementAndGet()
                    log("✅ 已在网 (${cost}ms)")
                }
                is SrunLogin.Result.Failed -> log("❌ 失败: ${result.reason}")
                is SrunLogin.Result.NoNetwork -> log("⚠️ 无网络 (可能未连上校园网)")
            }
            updateStatusNotification()
        } catch (e: Exception) {
            Log.e(TAG, "登录任务异常", e)
            log("异常: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            isLoginInFlight.set(false)
        }
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "$time  $msg"
        Log.d(TAG, line)
        synchronized(logLock) {
            logBuffer.addLast(line)
            while (logBuffer.size > maxLogLines) logBuffer.removeFirst()
            // 同步给 State,UI 可以读取
            State.logSnapshot.set(logBuffer.toList())
        }
    }

    /**
     * 供 MainActivity 调用,拉取最近 N 条日志
     */
    fun snapshotLogs(): List<String> = synchronized(logLock) { logBuffer.toList() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "校园网自动连接",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "监听 ZJUWLAN 连接状态,触发自动登录"
                setShowBadge(false)
            }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
    }

    private fun buildStatusNotification(title: String, content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateStatusNotification() {
        val lastResult = NetworkMonitorService.State.lastLoginResult.get()
        val ssid = State.lastSsid.get()
        val title = when {
            lastResult is SrunLogin.Result.Success -> "✅ 已登录"
            lastResult is SrunLogin.Result.AlreadyOnline -> "✅ 已在网"
            lastResult is SrunLogin.Result.Failed -> "❌ 上次登录失败"
            lastResult is SrunLogin.Result.NoNetwork -> "⚠️ 无网络"
            ssid != null && ssid in TARGET_SSIDS -> "📶 ZJUWLAN 已连接,登录中…"
            ssid != null -> "📶 已连: $ssid (非目标网络)"
            else -> "👀 监听中…"
        }
        val content = buildString {
            append("成功 ${State.successfulLogins.get()}/${State.totalAttempts.get()}")
            if (ssid != null) append("  ·  $ssid")
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildStatusNotification(title, content))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) { /* ignore */ }
        serviceScope.cancel()
        State.isRunning.set(false)
        super.onDestroy()
    }
}

