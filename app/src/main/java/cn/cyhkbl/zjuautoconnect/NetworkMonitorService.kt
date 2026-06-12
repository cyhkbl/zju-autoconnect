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
 * 触发逻辑:
 *  - 注册 NetworkCallback 监听所有 WiFi 网络
 *  - 连上 WiFi 后检查 SSID,匹配目标列表(ZJUWLAN) → 触发登录
 *  - 用 AtomicBoolean 防止并发登录
 *  - 如果 OkHttp 调 srun_portal API 失败(Result.NoNetwork),说明 captive portal
 *    未认证,启动 PortalHelperActivity(WebView 自动填表 + 提交)
 */
class NetworkMonitorService : Service() {

    // 全局状态(供 UI 读取)
    object State {
        val isRunning = AtomicBoolean(false)
        val lastSsid = AtomicReference<String?>(null)
        val lastLoginAt = AtomicReference<Long?>(null)
        val lastLoginResult = AtomicReference<SrunLogin.Result?>(null)
        val totalAttempts = AtomicInteger(0)
        val successfulLogins = AtomicInteger(0)
        val logSnapshot = AtomicReference<List<String>>(emptyList())
        val portalCompletedAt = AtomicReference<Long?>(null)
        val portalHelperRunning = AtomicBoolean(false)
    }

    companion object {
        private const val TAG = "NetMonService"
        private const val CHANNEL_ID = "zju_autoconnect_status"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "cn.cyhkbl.zjuautoconnect.START"
        private const val ACTION_STOP = "cn.cyhkbl.zjuautoconnect.STOP"
        private const val ACTION_RETRY = "cn.cyhkbl.zjuautoconnect.RETRY"

        // 浙大校园网 SSID — 触发自动登录的目标
        val TARGET_SSIDS = setOf("ZJUWLAN", "ZJUWLAN-Secure", "ZJUWLAN-AUTO")

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

        /**
         * Portal 助手完成后调用,让 service 重新尝试 OkHttp 登录
         */
        fun requestRetryAfterPortal(context: Context) {
            State.portalCompletedAt.set(System.currentTimeMillis())
            val intent = Intent(context, NetworkMonitorService::class.java)
                .setAction(ACTION_RETRY)
            context.startService(intent)
        }
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

    // 等待 WiFi 通过 captive portal 验证的最长时间
    private val portalWaitTimeoutMs = 30_000L
    private val maxLoginRetries = 3
    private val loginRetryDelayMs = 5_000L

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
        when (intent?.action) {
            ACTION_RETRY -> {
                log("📨 收到 ACTION_RETRY(portal 完成后)")
                serviceScope.launch { attemptLogin() }
            }
            else -> {
                serviceScope.launch { checkCurrentWifiOnce() }
            }
        }
        return START_STICKY
    }

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

            log("等待 WiFi 通过 captive portal 验证…")
            val portalOk = waitForWifiValidated(portalWaitTimeoutMs)
            if (!portalOk) {
                log("⚠️ ${portalWaitTimeoutMs / 1000} 秒内 WiFi 未通过 portal 验证 → 启动 Portal 助手自动填表")
                launchPortalHelper()
            } else {
                log("✅ WiFi 已通过 portal 验证,直接登录")
            }

            var lastResult: SrunLogin.Result = SrunLogin.Result.Failed("尚未尝试")
            for (attempt in 1..maxLoginRetries) {
                State.totalAttempts.incrementAndGet()
                val start = System.currentTimeMillis()
                State.lastLoginAt.set(start)
                log("▶ 登录尝试 $attempt/$maxLoginRetries (user=${username.takeLast(4).padStart(username.length, '*')})")
                updateStatusNotification()
                val result = SrunLogin.login(username, password)
                val cost = System.currentTimeMillis() - start
                lastResult = result
                State.lastLoginResult.set(result)
                when (result) {
                    is SrunLogin.Result.Success -> {
                        State.successfulLogins.incrementAndGet()
                        log("✅ 登录成功 (${cost}ms)")
                        updateStatusNotification()
                        return
                    }
                    is SrunLogin.Result.AlreadyOnline -> {
                        State.successfulLogins.incrementAndGet()
                        log("✅ 已在网 (${cost}ms)")
                        updateStatusNotification()
                        return
                    }
                    is SrunLogin.Result.Failed -> {
                        log("❌ 第 $attempt 次失败: ${result.reason}")
                    }
                    is SrunLogin.Result.NoNetwork -> {
                        log("⚠️ 第 $attempt 次无网络")
                        // 第 1 次 NoNetwork 时启动 Portal 助手
                        if (attempt == 1) launchPortalHelper()
                    }
                }
                if (attempt < maxLoginRetries) {
                    log("⏳ 等待 ${loginRetryDelayMs / 1000} 秒后重试…")
                    kotlinx.coroutines.delay(loginRetryDelayMs)
                }
            }
            when (lastResult) {
                is SrunLogin.Result.NoNetwork ->
                    log("❌ ${maxLoginRetries} 次重试仍无网络。Portal 助手正在处理(透明 Activity 自动填表),完成后会重试。")
                is SrunLogin.Result.Failed ->
                    log("❌ ${maxLoginRetries} 次重试仍失败: ${lastResult.reason}")
                else -> {}
            }
            updateStatusNotification()
        } catch (e: Exception) {
            Log.e(TAG, "登录任务异常", e)
            log("异常: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            isLoginInFlight.set(false)
        }
    }

    /**
     * 启动 Portal 助手(WebView 自动填 + 提交)
     * Service 里启动 Activity 需要 FLAG_ACTIVITY_NEW_TASK
     */
    private fun launchPortalHelper() {
        if (State.portalHelperRunning.get()) {
            log("Portal 助手已在运行,跳过")
            return
        }
        State.portalHelperRunning.set(true)
        try {
            val intent = Intent(this, PortalHelperActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            log("🤖 已启动 Portal 助手(WebView 自动填表)")
        } catch (e: Exception) {
            Log.e(TAG, "启动 Portal 助手失败", e)
            log("❌ 启动 Portal 助手失败: ${e.message}")
            State.portalHelperRunning.set(false)
        }
    }

    private suspend fun waitForWifiValidated(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val active = connectivityManager.activeNetwork
            if (active == null) {
                kotlinx.coroutines.delay(500)
                continue
            }
            val caps = connectivityManager.getNetworkCapabilities(active)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                return true
            }
            kotlinx.coroutines.delay(500)
        }
        return false
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "$time  $msg"
        Log.d(TAG, line)
        synchronized(logLock) {
            logBuffer.addLast(line)
            while (logBuffer.size > maxLogLines) logBuffer.removeFirst()
            State.logSnapshot.set(logBuffer.toList())
        }
    }

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
            State.portalHelperRunning.get() -> "🤖 自动完成 portal 认证中…"
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
