package cn.cyhkbl.zjuautoconnect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启 + 应用被替换/升级时重启服务
 *
 * 注意:MIUI / EMUI / ColorOS 等深度定制 ROM 默认会禁止应用自启,
 * 用户需在系统设置里给 ZJU-AutoConnect 开启"自启动"和"后台运行"
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "收到广播: $action")
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (PrefsManager.isAutoStartOnBoot(context)) {
                    val (u, p) = PrefsManager.getCredentials(context)
                    if (u.isNotBlank() && p.isNotBlank()) {
                        Log.d(TAG, "启动 NetworkMonitorService")
                        NetworkMonitorService.start(context)
                    } else {
                        Log.d(TAG, "未配置账号,跳过自启")
                    }
                }
            }
        }
    }
}
