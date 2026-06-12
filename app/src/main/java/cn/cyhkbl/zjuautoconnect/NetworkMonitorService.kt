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
        val lastSsid = AtomicReferenceExt<String?>(null)
        val lastLoginAt = AtomicReferenceExt<Long?>(null)
        val lastLoginResult = AtomicReferenceExt<SrunLogin.Result?>(null)
        val totalAttempts = AtomicInteger(0)
        val successfulLogins = AtomicInteger(0)
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
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                onWifiConnected()
            }
        }

        override fun onLost(network: Network) {
            log("WiFi 断开")
            State.lastSsid.set(null)
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
        // 启动时立刻检查一次当前 WiFi(可能服务启动前已经连上了)
        serviceScope.launch { checkCurrentWifiAndLogin() }
        return START_STICKY
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

    private fun onWifiConnected() {
        serviceScope.launch {
            checkCurrentWifiAndLogin()
        }
    }

    /**
     * 检查当前连接的 WiFi,如果在目标 SSID 列表中则触发登录
     */
    private suspend fun checkCurrentWifiAndLogin() {
        val ssid = readCurrentSsid() ?: return
        State.lastSsid.set(ssid)
        log("已连接: $ssid")
        updateStatusNotification()
        if (ssid !in TARGET_SSIDS) {
            log("非目标 SSID,跳过登录")
            return
        }
        attemptLogin()
    }

    /**
     * 读取当前 WiFi 的 SSID(兼容不同 Android 版本)
     *  - 优先 NetworkCapabilities(API 29+ 更准)
     *  - 兜底用 WifiManager.getConnectionInfo(API <26 唯一方式)
     */
    private fun readCurrentSsid(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: 走 NetworkCallback 里拿到的 caps
            val active = connectivityManager.activeNetwork ?: return null
            val caps = connectivityManager.getNetworkCapabilities(active) ?: return null
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
        val lastResult = State.lastLoginResult.get()
        val title = when (lastResult) {
            is SrunLogin.Result.Success -> "✅ 已连网"
            is SrunLogin.Result.AlreadyOnline -> "✅ 已在网"
            is SrunLogin.Result.Failed -> "❌ 上次登录失败"
            is SrunLogin.Result.NoNetwork -> "⚠️ 无网络"
            null -> "监听中…"
        }
        val ssid = State.lastSsid.get()
        val content = buildString {
            if (ssid != null) append("SSID: $ssid  ·  ")
            append("成功 ${State.successfulLogins.get()}/${State.totalAttempts.get()}")
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

// 简易 atomic reference(避免引入 AtomicReference 类名冲突)
private class AtomicReferenceExt<T>(initial: T?) {
    private val ref = java.util.concurrent.atomic.AtomicReference<T?>(initial)
    fun get(): T? = ref.get()
    fun set(value: T?) = ref.set(value)
}
