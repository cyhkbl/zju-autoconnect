package cn.cyhkbl.zjuautoconnect

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Portal 自动认证助手 — 透明 Activity + WebView 加载浙大 mobile portal,
 * 自动填学号 + 密码 + 点登录,然后自动关闭。
 *
 * 关键发现(2026-06 抓的真实 DOM):
 *  - net.zju.edu.cn/ 会 meta refresh 到 srun_portal_pc
 *  - srun_portal_pc 用 redirect.js 自动跳到 srun_portal_phone(手机 UA 时)
 *  - 表单元素用 id 标识:
 *      <input type="text" id="username">
 *      <input type="password" id="password">
 *      <button type="button" id="login-account">登录</button>  ← type=button 不是 submit
 *  - 旧 JS 找 button[type=submit] 失败 + 没等 redirect 完成,导致填不进去
 *
 * 修复:
 *  1. 直接 loadUrl srun_portal_phone(跳过 PC 中转,减少跳转延迟)
 *  2. 用精确 id 选择器
 *  3. MutationObserver 监听 DOM 变化(Vue/异步渲染时反复尝试)
 *  4. 填表后 dispatch input 事件(框架可能需要)
 */
class PortalHelperActivity : Activity() {

    companion object {
        private const val TAG = "PortalHelper"
        // 跳过 PC 中转直接进 mobile 版(redirect.js 二次跳转在手机 UA 时只会绕一圈)
        private const val PORTAL_PHONE_URL =
            "https://net.zju.edu.cn/srun_portal_phone?ac_id=60&theme=zju"
    }

    private lateinit var webView: WebView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var finished = false
    private val statusView: TextView by lazy { TextView(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setGravity(Gravity.TOP or Gravity.START)

        statusView.text = getString(R.string.portal_helper_in_progress)
        statusView.setTextColor(Color.WHITE)
        statusView.textSize = 14f
        statusView.setPadding(48, 24, 48, 24)
        statusView.setBackgroundColor(0xCC1F2937.toInt())
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x33000000)
            gravity = Gravity.CENTER
            addView(statusView)
        }
        setContentView(container)

        setupWebView()
    }

    private fun setupWebView() {
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // 关键:用手机 UA,redirect.js 才会把 srun_portal_pc 跳到 srun_portal_phone
        webView.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        webView.visibility = WebView.INVISIBLE
        webView.layoutParams = ViewGroup.LayoutParams(1, 1)
        (findViewById<ViewGroup>(android.R.id.content)).addView(webView)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                Log.d(TAG, "onPageStarted: $url")
            }

            override fun onPageFinished(view: WebView, url: String) {
                Log.d(TAG, "onPageFinished: $url")
                // 渲染后 200ms 试一次(快速路径)
                view.postDelayed({ injectCredentials(view) }, 200)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean {
                Log.d(TAG, "redirect to: ${request.url}")
                // 允许 redirect.js 跳到 phone 版
                return false
            }
        }

        webView.loadUrl(PORTAL_PHONE_URL)
    }

    /**
     * 注入 JS:
     *  1. 立即尝试填表(用 id 选择器)
     *  2. 注册 MutationObserver,持续监听 DOM 变化(异步渲染时)
     *  3. 填过之后用 data-zju-filled 标记,防止重复填
     */
    private fun injectCredentials(view: WebView) {
        if (finished) return
        val (username, password) = PrefsManager.getCredentials(this)
        if (username.isBlank() || password.isBlank()) {
            finishWithError("未配置账号密码")
            return
        }

        val js = """
            (function() {
                try {
                    // 立即尝试一次
                    function tryFill() {
                        var userInput = document.getElementById('username');
                        var pwdInput = document.getElementById('password');
                        var btn = document.getElementById('login-account');
                        if (!userInput || !pwdInput || !btn) return false;
                        if (userInput.dataset && userInput.dataset.zjuFilled === '1') return true;
                        if (userInput.dataset) userInput.dataset.zjuFilled = '1';
                        userInput.value = '${username.replace("'", "\\'")}';
                        pwdInput.value = '${password.replace("'", "\\'")}';
                        // 触发 input 事件(Vue/React 框架可能需要)
                        userInput.dispatchEvent(new Event('input', {bubbles: true}));
                        pwdInput.dispatchEvent(new Event('input', {bubbles: true}));
                        userInput.dispatchEvent(new Event('change', {bubbles: true}));
                        pwdInput.dispatchEvent(new Event('change', {bubbles: true}));
                        btn.click();
                        return true;
                    }
                    var ok = tryFill();
                    // 即使立即成功,也注册 observer 兜底(异步渲染场景)
                    if (document.body) {
                        new MutationObserver(function() {
                            tryFill();
                        }).observe(document.body, {childList: true, subtree: true});
                    }
                    return JSON.stringify({ok: ok, body: !!document.body});
                } catch (e) {
                    return JSON.stringify({ok: false, error: e.toString()});
                }
            })();
        """.trimIndent()

        view.evaluateJavascript(js) { result ->
            Log.d(TAG, "inject result: $result")
            scope.launch {
                if (result == null || result.contains("\"ok\":false")) {
                    val reason = result ?: "未知"
                    // 立即注入失败不要立刻放弃,等 2 秒再试一次
                    delay(2000)
                    view.evaluateJavascript(js) { retryResult ->
                        Log.d(TAG, "retry inject result: $retryResult")
                        if (retryResult != null && !retryResult.contains("\"ok\":false")) {
                            onFilled()
                        } else {
                            finishWithError("填表失败: $retryResult")
                        }
                    }
                } else {
                    onFilled()
                }
            }
        }
    }

    private fun onFilled() {
        statusView.text = "⏳ 等待 portal 响应…"
        // 8 秒后关闭,给 form 提交 + portal 跳转时间
        scope.launch {
            delay(8000)
            finishWithSuccess()
        }
    }

    private fun finishWithSuccess() {
        if (finished) return
        finished = true
        runOnUiThread {
            Toast.makeText(this, R.string.portal_helper_done, Toast.LENGTH_SHORT).show()
            Log.d(TAG, "portal 认证流程完成,关闭 Activity")
            finish()
            NetworkMonitorService.State.portalCompletedAt.set(System.currentTimeMillis())
            NetworkMonitorService.requestRetryAfterPortal(this)
        }
    }

    private fun finishWithError(reason: String) {
        if (finished) return
        finished = true
        runOnUiThread {
            statusView.text = getString(R.string.portal_helper_failed) + "\n$reason"
            Log.e(TAG, "portal 助手失败: $reason")
            Toast.makeText(this, R.string.portal_helper_failed, Toast.LENGTH_LONG).show()
            scope.launch {
                delay(3000)
                finish()
            }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        scope.cancel()
        super.onDestroy()
    }
}
