package cn.cyhkbl.zjuautoconnect

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.floor
import kotlin.random.Random

/**
 * 浙大深澜 srun_bx1 认证登录 — 100% 移植自 internet.pyw
 *
 * 与 PC 版区别:
 *  - 用 OkHttp 替代 requests
 *  - 强制不走系统代理(对应 PC 版的 trust_env=False)
 *  - 跳过 SSL 验证(对应 PC 版的 verify=False)
 *  - 全部走 IO 协程,避免阻塞主线程
 */
object SrunLogin {

    private const val TAG = "SrunLogin"
    private const val URL_BASE = "https://net.zju.edu.cn"
    private const val N = "200"
    private const val TYPE = "1"
    private const val ENC = "srun_bx1"

    sealed class Result {
        object Success : Result()
        object AlreadyOnline : Result()
        data class Failed(val reason: String) : Result()
        object NoNetwork : Result()
    }

    /**
     * 构造一个不走代理、跳过 SSL 验证的 OkHttpClient,
     * 对应 PC 脚本的 session.trust_env=False + verify=False
     */
    private fun buildClient(): OkHttpClient {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ssl = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }

        return OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)  // 强制无代理
            .sslSocketFactory(ssl.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)   // portal 刚认证完时路由切换可能慢
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)        // 失败就让人工决定,不要自动重试(我们的 retry 在外层)
            .build()
    }

    /**
     * 执行一次完整登录流程
     * @param username 学号
     * @param password 校园网密码
     */
    suspend fun login(username: String, password: String): Result = withContext(Dispatchers.IO) {
        val client = buildClient()
        try {
            // 1. 初始化,获取 ac_id 与 ip(可能需要跟随 meta refresh)
            val firstHtml = fetch(client, "$URL_BASE/")
            val (acId, ip) = parseInit(client, firstHtml)
                ?: return@withContext Result.Failed("解析 ac_id/ip 失败")

            // 2. 拿 challenge
            val randnum = (1..2_000_000_000_000_000_000L).random()
            val timestamp = System.currentTimeMillis()
            val challengeUrl = "$URL_BASE/cgi-bin/get_challenge?callback=jQuery${randnum}_${timestamp}" +
                "&username=${username}&ip=${ip}&_=${timestamp}"
            val challengeResp = fetch(client, challengeUrl)
            val token = TOKEN_RE.find(challengeResp)?.groupValues?.get(1)
                ?: return@withContext Result.Failed("challenge 解析失败")

            // 3. 构造 info + hmd5 + chksum
            val info = SrunCrypto.buildInfo(username, password, ip, acId, token)
            val hmd5 = SrunCrypto.getMd5(password, token)
            val chksum = SrunCrypto.buildChksum(token, username, hmd5, acId, ip, info)

            // 4. 提交登录
            val loginTs = System.currentTimeMillis()
            val loginUrl = "$URL_BASE/cgi-bin/srun_portal?callback=jQuery${randnum}_${loginTs}" +
                "&action=login" +
                "&username=${username}" +
                "&password={MD5}${hmd5}" +
                "&ac_id=${acId}" +
                "&ip=${ip}" +
                "&chksum=${chksum}" +
                "&info=${info}" +
                "&n=${N}" +
                "&type=${TYPE}" +
                "&os=android" +
                "&name=android" +
                "&double_stack=0" +
                "&_=${loginTs}"
            val loginResp = fetch(client, loginUrl)
            Log.d(TAG, "login resp: ${loginResp.take(300)}")

            return@withContext when {
                "E0000" in loginResp -> Result.Success
                "ip_already_online_error" in loginResp -> Result.AlreadyOnline
                "login_error" in loginResp -> {
                    // 提取错误原因
                    val m = ERROR_RE.find(loginResp)
                    Result.Failed(m?.groupValues?.get(1) ?: "login_error")
                }
                else -> Result.Failed("未知响应: ${loginResp.take(120)}")
            }
        } catch (e: java.net.UnknownHostException) {
            Result.NoNetwork
        } catch (e: java.net.SocketTimeoutException) {
            Result.NoNetwork
        } catch (e: Exception) {
            Log.e(TAG, "登录异常", e)
            Result.Failed("异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun fetch(client: OkHttpClient, url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android)")
            .header("Accept", "*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            return resp.body?.string() ?: ""
        }
    }

    /**
     * 解析初始化页:可能通过 meta refresh 跳转,然后从第二个响应的 JS 变量里取值
     * 对应 PC 脚本里的两种正则:
     *   - 跳转后页面:  acid   : "(.*?)"   /   ip     : "(.*?)"
     *   - 表单页面:    id="ac_id" value="(.*?)"
     */
    private fun parseInit(client: OkHttpClient, html: String): Pair<String, String>? {
        // 路径 A: meta refresh
        val m1 = META_REFRESH_RE.find(html)
        if (m1 != null) {
            val raw = m1.groupValues[1].replace("&amp;", "&")
            val target = if (raw.startsWith("http")) raw else URL_BASE + raw
            val second = fetch(client, target)
            val ac = AC_ID_JS_RE.find(second)?.groupValues?.get(1)
            val ip = IP_JS_RE.find(second)?.groupValues?.get(1)
            if (ac != null && ip != null) return ac to ip
            // 兜底: 跳转后页面也可能有表单字段
            val ac2 = AC_ID_FORM_RE.find(second)?.groupValues?.get(1)
            val ip2 = USER_IP_RE.find(second)?.groupValues?.get(1)
            if (ac2 != null && ip2 != null) return ac2 to ip2
            return null
        }
        // 路径 B: 表单字段
        val ac = AC_ID_FORM_RE.find(html)?.groupValues?.get(1)
        val ip = USER_IP_RE.find(html)?.groupValues?.get(1)
        if (ac != null && ip != null) return ac to ip
        return null
    }

    private val META_REFRESH_RE = Regex(
        """<meta\s+http-equiv=["']refresh["']\s+content=["'][^"']*url=([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    // 跳转后 JS 变量: acid   : "1"   /   ip     : "10.0.0.1"
    private val AC_ID_JS_RE = Regex("""acid\s*:\s*"([^"]+)"""")
    private val IP_JS_RE = Regex("""ip\s*:\s*"([^"]+)"""")
    // 表单字段: id="ac_id" value="1"
    private val AC_ID_FORM_RE = Regex("""id=["']ac_id["']\s+value=["']([^"']+)["']""")
    private val USER_IP_RE = Regex("""id=["']user_ip["']\s+value=["']([^"']+)["']""")
    private val TOKEN_RE = Regex(""""challenge"\s*:\s*"([^"]+)"""")
    private val ERROR_RE = Regex("""error["']?\s*:\s*["']?([^,"'}]+)""")
}
