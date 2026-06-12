package cn.cyhkbl.zjuautoconnect

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 配置存储 — 用 EncryptedSharedPreferences 加密保存学号和密码
 *
 * 首次启动时,如果 EncryptedSharedPreferences 不可用(理论上不会发生,
 * 但 Tink 在某些定制 ROM 上会出问题),降级到普通 SharedPreferences
 */
object PrefsManager {

    private const val TAG = "PrefsManager"
    private const val FILE_NAME = "zju_autoconnect_prefs"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_AUTO_START = "auto_start_on_boot"

    /**
     * 返回带安全降级的 SharedPreferences
     */
    private fun prefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences 不可用,降级到普通 prefs", e)
            context.getSharedPreferences(FILE_NAME + "_fallback", Context.MODE_PRIVATE)
        }
    }

    fun getCredentials(context: Context): Pair<String, String> {
        val p = prefs(context)
        val u = p.getString(KEY_USERNAME, "") ?: ""
        val w = p.getString(KEY_PASSWORD, "") ?: ""
        return u to w
    }

    fun setCredentials(context: Context, username: String, password: String) {
        prefs(context).edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun isAutoStartOnBoot(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_START, true)
    }

    fun setAutoStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }
}
