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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Portal 自动认证助手 — 用透明 Activity + WebView 加载浙大 portal 登录页,
 * 自动填学号 + 密码,自动点登录,然后自动关闭。
 *
 * 触发时机:Service 监听到 ZJUWLAN + OkHttp 调 srun_portal API 失败(说明 system 还在等 portal 认证)
 * 目标:用户不需要看到任何界面,App 静默完成 portal 认证。
 */
class PortalHelperActivity : Activity() {

    companion object {
        private const val TAG = "PortalHelper"
        private const val PORTAL_URL = "https://net.zju.edu.cn/"
    }

    private lateinit var webView: WebView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var hasInjected = false
    private var finished = false
    private val statusView: TextView by lazy { TextView(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 透明背景,看起来就像悬浮层
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setGravity(Gravity.TOP or Gravity.START)

        // 状态显示:全屏覆盖一个 TextView,提示正在自动认证(用户能看见)
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
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        // 隐藏 WebView(透明 Activity 内的),但保持运行
        webView.visibility = WebView.INVISIBLE
        webView.layoutParams = ViewGroup.LayoutParams(1, 1)
        (findViewById<ViewGroup>(android.R.id.content)).addView(webView)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                Log.d(TAG, "onPageStarted: $url")
            }

            override fun onPageFinished(view: WebView, url: String) {
                Log.d(TAG, "onPageFinished: $url")
                if (hasInjected) return
                // 等 JS 渲染好表单
                view.postDelayed({ injectCredentials(view) }, 600)
            }
        }

        webView.loadUrl(PORTAL_URL)
    }

    /**
     * 注入 JS,自动填学号 + 密码 + 提交
     *
     * 不依赖具体 input id,通用方案:
     *  - 找第一个 type="text"/"number"/"tel" 的 input(用户名)
     *  - 找 type="password" 的 input(密码)
     *  - 找 type="submit" 按钮或第一个 button
     */
    private fun injectCredentials(view: WebView) {
        if (hasInjected || finished) return
        val (username, password) = PrefsManager.getCredentials(this)
        if (username.isBlank() || password.isBlank()) {
            finishWithError("未配置账号密码")
            return
        }

        val js = """
            (function() {
                try {
                    var inputs = document.querySelectorAll('input');
                    var userInput = null, pwdInput = null;
                    for (var i = 0; i < inputs.length; i++) {
                        var t = (inputs[i].type || '').toLowerCase();
                        if (t === 'password') {
                            pwdInput = inputs[i];
                        } else if (t === 'text' || t === 'number' || t === 'tel' || t === 'email') {
                            if (!userInput) userInput = inputs[i];
                        }
                    }
                    if (!userInput || !pwdInput) {
                        return JSON.stringify({ok: false, reason: '未找到表单 input'});
                    }
                    userInput.value = '${username.replace("'", "\\'")}';
                    pwdInput.value = '${password.replace("'", "\\'")}';
                    userInput.dispatchEvent(new Event('input', {bubbles: true}));
                    pwdInput.dispatchEvent(new Event('input', {bubbles: true}));

                    // 找 submit 按钮
                    var submit = document.querySelector('button[type="submit"]')
                              || document.querySelector('input[type="submit"]')
                              || document.querySelector('button[id*="login" i]')
                              || document.querySelector('button[name*="login" i]')
                              || document.querySelector('button.btn-primary')
                              || document.querySelector('form button');
                    if (!submit) {
                        // 找不到按钮,尝试 form.submit()
                        var form = userInput.form || pwdInput.form;
                        if (form) { form.submit(); return JSON.stringify({ok: true, way: 'form.submit'}); }
                        return JSON.stringify({ok: false, reason: '未找到提交按钮'});
                    }
                    submit.click();
                    return JSON.stringify({ok: true});
                } catch (e) {
                    return JSON.stringify({ok: false, reason: e.toString()});
                }
            })();
        """.trimIndent()

        view.evaluateJavascript(js) { result ->
            Log.d(TAG, "inject result: $result")
            scope.launch {
                if (result == null || result.contains("\"ok\":false")) {
                    val reason = result ?: "未知"
                    finishWithError("填表失败: $reason")
                    return@launch
                }
                // 成功注入,等 4-5 秒让表单提交 + portal 跳转
                hasInjected = true
                statusView.text = "⏳ 等待 portal 响应…"
                delay(5000)
                finishWithSuccess()
            }
        }
    }

    private fun finishWithSuccess() {
        if (finished) return
        finished = true
        runOnUiThread {
            Toast.makeText(this, R.string.portal_helper_done, Toast.LENGTH_SHORT).show()
            Log.d(TAG, "portal 认证流程完成,关闭 Activity")
            finish()
            // 通知 service:portal 完成,可以重试 OkHttp 登录
            NetworkMonitorService.State.portalCompletedAt.set(System.currentTimeMillis())
            NetworkMonitorService.requestRetryAfterPortal(this)
        }
    }

    private fun finishWithError(reason: String) {
        if (finished) return
        finished = true
        runOnUiThread {
            statusView.text = getString(R.string.portal_helper_failed)
            Log.e(TAG, "portal 助手失败: $reason")
            Toast.makeText(this, R.string.portal_helper_failed, Toast.LENGTH_LONG).show()
            // 2 秒后关闭,让用户看到错误提示
            scope.launch {
                delay(2500)
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
