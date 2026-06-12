package cn.cyhkbl.zjuautoconnect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.cyhkbl.zjuautoconnect.databinding.ActivityMainBinding
import cn.cyhkbl.zjuautoconnect.databinding.ItemLogBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面
 *
 * UI 区:
 *  - 状态卡片(显示服务状态 + 累计成功/尝试)
 *  - 账号设置(学号 + 密码)
 *  - 服务控制(启动/停止 + 手动登录 + 开机自启开关)
 *  - 日志区
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val logAdapter = LogAdapter()

    private val requestNotificationPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            tryStartService()
        } else {
            showPermissionDialog(
                "需要通知权限",
                "未授予通知权限,前台服务将无法启动。请到设置里开启通知权限。"
            )
        }
    }

    private val requestLocationPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "无位置权限时,无法读取当前连接的 WiFi 名称", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLogList()
        setupListeners()
        loadCredentials()
    }

    private fun setupLogList() {
        binding.rvLogs.layoutManager = LinearLayoutManager(this)
        binding.rvLogs.adapter = logAdapter
    }

    private fun setupListeners() {
        binding.btnSave.setOnClickListener { saveCredentials() }
        binding.btnToggleService.setOnClickListener { onToggleServiceClicked() }
        binding.btnManualLogin.setOnClickListener { onManualLoginClicked() }
        binding.btnClearLogs.setOnClickListener {
            logAdapter.submit(emptyList())
        }
        binding.swAutoStart.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setAutoStartOnBoot(this, isChecked)
        }
    }

    private fun loadCredentials() {
        val (u, p) = PrefsManager.getCredentials(this)
        binding.etUsername.setText(u)
        binding.etPassword.setText(p)
        binding.swAutoStart.isChecked = PrefsManager.isAutoStartOnBoot(this)
    }

    private fun saveCredentials() {
        val u = binding.etUsername.text?.toString()?.trim().orEmpty()
        val p = binding.etPassword.text?.toString().orEmpty()
        if (u.isBlank() || p.isBlank()) {
            Toast.makeText(this, R.string.msg_fill_both, Toast.LENGTH_SHORT).show()
            return
        }
        PrefsManager.setCredentials(this, u, p)
        Toast.makeText(this, R.string.msg_credentials_saved, Toast.LENGTH_SHORT).show()
    }

    private fun onToggleServiceClicked() {
        if (NetworkMonitorService.State.isRunning.get()) {
            NetworkMonitorService.stop(this)
            Toast.makeText(this, R.string.msg_service_stopped, Toast.LENGTH_SHORT).show()
        } else {
            ensurePermissionsAndStart()
        }
    }

    private fun ensurePermissionsAndStart() {
        // Android 13+ 通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        // Android 13+ 读取 SSID 需要位置权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestLocationPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                // 不 return,允许服务先起来,只是 SSID 读不到时会跳过登录
            }
        }
        tryStartService()
    }

    private fun tryStartService() {
        val (u, p) = PrefsManager.getCredentials(this)
        if (u.isBlank() || p.isBlank()) {
            Toast.makeText(this, R.string.msg_fill_both, Toast.LENGTH_SHORT).show()
            return
        }
        NetworkMonitorService.start(this)
        Toast.makeText(this, R.string.msg_service_started, Toast.LENGTH_SHORT).show()
    }

    private fun onManualLoginClicked() {
        val (u, p) = PrefsManager.getCredentials(this)
        if (u.isBlank() || p.isBlank()) {
            Toast.makeText(this, R.string.msg_fill_both, Toast.LENGTH_SHORT).show()
            return
        }
        // 先确保服务在跑(否则后台登录完后进程被回收)
        if (!NetworkMonitorService.State.isRunning.get()) {
            ensurePermissionsAndStart()
        }
        binding.btnManualLogin.isEnabled = false
        binding.btnManualLogin.text = "登录中…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { SrunLogin.login(u, p) }
            binding.btnManualLogin.isEnabled = true
            binding.btnManualLogin.setText(R.string.btn_manual_login)
            val msg = when (result) {
                is SrunLogin.Result.Success -> getString(R.string.msg_login_success)
                is SrunLogin.Result.AlreadyOnline -> getString(R.string.msg_login_already_online)
                is SrunLogin.Result.Failed -> getString(R.string.msg_login_failed, result.reason)
                is SrunLogin.Result.NoNetwork -> getString(R.string.msg_login_no_network)
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
    }

    private fun showPermissionDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshLogs()
            refreshHandler.postDelayed(this, 1000)  // 每秒刷一次
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun refreshStatus() {
        val running = NetworkMonitorService.State.isRunning.get()
        binding.tvRunningDot.text = if (running) "●" else "○"
        binding.tvRunningDot.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.success else R.color.text_tertiary)
        )
        binding.btnToggleService.setText(
            if (running) R.string.btn_stop_service else R.string.btn_start_service
        )

        val result = NetworkMonitorService.State.lastLoginResult.get()
        val (text, sub, iconRes) = when (result) {
            is SrunLogin.Result.Success -> Triple(
                getString(R.string.status_logged_in), "上次登录成功", R.drawable.ic_check
            )
            is SrunLogin.Result.AlreadyOnline -> Triple(
                getString(R.string.status_already_online), "设备已在网", R.drawable.ic_check
            )
            is SrunLogin.Result.Failed -> Triple(
                getString(R.string.status_failed), result.reason, R.drawable.ic_close
            )
            is SrunLogin.Result.NoNetwork -> Triple(
                getString(R.string.status_no_network), "请确认连接 ZJUWLAN", R.drawable.ic_wifi
            )
            null -> Triple(
                if (running) getString(R.string.status_monitoring) else getString(R.string.status_idle),
                "——",
                R.drawable.ic_info
            )
        }
        binding.tvStatusText.text = text
        binding.tvStatusSub.text = sub
        binding.ivStatusIcon.setImageResource(iconRes)

        binding.tvSuccessCount.text = NetworkMonitorService.State.successfulLogins.get().toString()
        binding.tvTotalCount.text = NetworkMonitorService.State.totalAttempts.get().toString()
    }

    private fun refreshLogs() {
        // 从 State 读服务的日志快照(服务跟 Activity 同进程)
        logAdapter.submit(NetworkMonitorService.State.logSnapshot.get())
    }

    /**
     * 日志适配器 — 简化版,展示最近 50 条
     */
    private class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {
        private val items = mutableListOf<String>()
        fun submit(list: List<String>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }
        class VH(val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.binding.tvLogLine.text = items[position]
        }
        override fun getItemCount() = items.size
    }
}
