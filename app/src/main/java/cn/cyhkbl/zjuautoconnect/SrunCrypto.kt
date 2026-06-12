package cn.cyhkbl.zjuautoconnect

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 深澜 srun_bx1 认证加密算法 — 100% 移植自 PC 脚本 internet.pyw
 *
 * 注意：Kotlin Int 是 32 位有符号,Python int 是任意精度。所有算术在 mod 2^32
 * 之后等价,位运算用 shr(有符号算术右移)与 Python 一致。
 */
object SrunCrypto {

    private const val URL_BASE = "https://net.zju.edu.cn"
    private const val PADCHAR = "="
    private const val ALPHA = "LVoJPiCN2R8G90yg+hmFHuacZ1OWMnrsSTXkYpUq/3dlbfKwv6xztjI7DeBE45QA"
    private const val N = "200"
    private const val TYPE = "1"
    private const val ENC = "srun_bx1"

    // ========== XEncode 加密核心 ==========

    private fun force(msg: String): ByteArray = msg.toByteArray(Charsets.UTF_8)

    private fun ordat(msg: ByteArray, idx: Int): Int =
        if (msg.size > idx) (msg[idx].toInt() and 0xFF) else 0

    private fun sencode(msg: ByteArray, key: Boolean): IntArray {
        val l = msg.size
        val pwd = IntArray((l + 3) / 4 + (if (key) 1 else 0))
        var pi = 0
        var i = 0
        while (i < l) {
            pwd[pi++] = ordat(msg, i) or
                (ordat(msg, i + 1) shl 8) or
                (ordat(msg, i + 2) shl 16) or
                (ordat(msg, i + 3) shl 24)
            i += 4
        }
        if (key) pwd[pi] = l
        return pwd
    }

    private fun lencode(msg: IntArray, key: Boolean): String {
        val l = msg.size
        val ll = (l - 1) shl 2
        val effectiveLl = if (key) {
            val m = msg[l - 1]
            // 与 Python 一致:越界则直接 return null(调用方会得到空字符串)
            if (m < ll - 3 || m > ll) return ""
            m
        } else ll

        val sb = StringBuilder(l * 4)
        for (i in 0 until l) {
            val v = msg[i]
            sb.append((v and 0xFF).toChar())
            sb.append((v shr 8 and 0xFF).toChar())
            sb.append((v shr 16 and 0xFF).toChar())
            sb.append((v shr 24 and 0xFF).toChar())
        }
        return if (key) sb.toString().substring(0, effectiveLl) else sb.toString()
    }

    /**
     * XEncode 加密 — 浙大 srun_bx1 用的就是这个
     */
    fun getXencode(msg: String, key: String): String {
        if (msg.isEmpty()) return ""
        val pwd = sencode(force(msg), true)
        val keyBytes = force(key)
        var pwdk = sencode(keyBytes, false).toMutableList()
        if (pwdk.size < 4) {
            while (pwdk.size < 4) pwdk.add(0)
        }
        val pwdkArr = pwdk.toIntArray()

        val n = pwd.size - 1
        var z = pwd[n]
        val c = 0x86014019.toInt() or 0x183639A0.toInt()  // 0x9E3779B9
        var q = Math.floor(6.0 + 52.0 / (n + 1)).toInt()
        var d = 0

        while (q > 0) {
            d = (d + c) and 0xFFFFFFFF.toInt()
            val e = d ushr 2 and 3
            var p = 0
            while (p < n) {
                val y = pwd[p + 1]
                var m = (z ushr 5) xor (y shl 2)
                m = m + (((y ushr 3) xor (z shl 4)) xor (d xor y))
                m = m + ((pwdkArr[(p and 3) xor e] xor z))
                pwd[p] = (pwd[p] + m) and 0xFFFFFFFF.toInt()
                z = pwd[p]
                p++
            }
            val y0 = pwd[0]
            var m0 = (z ushr 5) xor (y0 shl 2)
            m0 = m0 + (((y0 ushr 3) xor (z shl 4)) xor (d xor y0))
            m0 = m0 + ((pwdkArr[(n and 3) xor e] xor z))
            pwd[n] = (pwd[n] + m0) and 0xFFFFFFFF.toInt()
            z = pwd[n]
            q--
        }
        return lencode(pwd, false)
    }

    // ========== 自定义 Base64 ==========

    private fun getByte(s: String, i: Int): Int {
        val x = s[i].code
        if (x > 255) error("Invalid char: ${s[i]}")
        return x
    }

    /**
     * 用深澜自定义字母表做 Base64 编码
     */
    fun getBase64(s: String): String {
        if (s.isEmpty()) return s
        val x = StringBuilder()
        val imax = s.length - s.length % 3
        var i = 0
        while (i < imax) {
            val b10 = (getByte(s, i) shl 16) or
                (getByte(s, i + 1) shl 8) or
                getByte(s, i + 2)
            x.append(ALPHA[(b10 shr 18)])
            x.append(ALPHA[((b10 shr 12) and 63)])
            x.append(ALPHA[((b10 shr 6) and 63)])
            x.append(ALPHA[(b10 and 63)])
            i += 3
        }
        val rem = s.length - imax
        if (rem == 1) {
            val b10 = getByte(s, imax) shl 16
            x.append(ALPHA[(b10 shr 18)])
            x.append(ALPHA[((b10 shr 12) and 63)])
            x.append(PADCHAR)
            x.append(PADCHAR)
        } else if (rem == 2) {
            val b10 = (getByte(s, imax) shl 16) or (getByte(s, imax + 1) shl 8)
            x.append(ALPHA[(b10 shr 18)])
            x.append(ALPHA[((b10 shr 12) and 63)])
            x.append(ALPHA[((b10 shr 6) and 63)])
            x.append(PADCHAR)
        }
        return x.toString()
    }

    // ========== HMAC-MD5 & SHA1 ==========

    fun getMd5(password: String, token: String): String {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacMD5"))
        return mac.doFinal(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    fun getSha1(value: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    // ========== 组合:构造 info 字符串 ==========

    /**
     * 构造 srun_bx1 的 info 字段,直接对应 Python 脚本里的:
     *   info_temp = {"username":..., "password":..., "ip":..., "acid":..., "enc_ver":...}
     *   i_str = re.sub("'", '"', str(info_temp))
     *   i_str = re.sub(" ", '', i_str)
     *   i_enc = "{SRBX1}" + get_base64(get_xencode(i_str, token))
     */
    fun buildInfo(username: String, password: String, ip: String, acId: String, token: String): String {
        val infoTemp = "{\"username\":\"$username\",\"password\":\"$password\",\"ip\":\"$ip\",\"acid\":\"$acId\",\"enc_ver\":\"$ENC\"}"
        return "{SRBX1}" + getBase64(getXencode(infoTemp, token))
    }

    /**
     * 构造 chksum: token + username + token + hmd5 + token + acid + token + ip + token + N + token + TYPE + token + i_enc
     */
    fun buildChksum(token: String, username: String, hmd5: String, acId: String, ip: String, iEnc: String): String {
        val s = token + username + token + hmd5 + token + acId + token +
            ip + token + N + token + TYPE + token + iEnc
        return getSha1(s)
    }
}
