package cn.cyhkbl.zjuautoconnect

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 单元测试 — 与 Python internet.pyw 的输出一致
 *
 * Expected 值通过运行 /home/henry/internet.pyw 算得,
 * 这里硬编码以保证跨语言实现一致。
 *
 * 在 CI (GitHub Actions) 上跑: ./gradlew :app:testDebugUnitTest
 */
class SrunCryptoTest {

    @Test
    fun `getXencode empty string returns empty`() {
        assertEquals("", SrunCrypto.getXencode("", "abc"))
    }

    @Test
    fun `getXencode ascii matches Python`() {
        // get_xencode("a", "k") = bytes 10 d1 88 dc 61 52 2d 85
        val actual = SrunCrypto.getXencode("a", "k")
        val expectedHex = "10d188dc61522d85"
        assertEquals(
            "hex mismatch",
            expectedHex,
            actual.toByteArray(Charsets.ISO_8859_1).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        )
    }

    @Test
    fun `getXencode multi-byte string matches Python`() {
        // get_xencode("hello world", "foobar") hex = d68edf8f163d8d8f08736f9d111eb18c
        val actual = SrunCrypto.getXencode("hello world", "foobar")
        val expectedHex = "d68edf8f163d8d8f08736f9d111eb18c"
        assertEquals(
            expectedHex,
            actual.toByteArray(Charsets.ISO_8859_1).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        )
    }

    @Test
    fun `getBase64 single byte matches Python`() {
        // get_base64("A") = "++==",  get_base64("AB") = "+H2=",  get_base64("ABC") = "+HRJ"
        assertEquals("++==", SrunCrypto.getBase64("A"))
        assertEquals("+H2=", SrunCrypto.getBase64("AB"))
        assertEquals("+HRJ", SrunCrypto.getBase64("ABC"))
        assertEquals("+HRJhL==", SrunCrypto.getBase64("ABCD"))
        assertEquals("+HRJhPH=", SrunCrypto.getBase64("ABCDE"))
    }

    @Test
    fun `getMd5 matches Python hmac md5`() {
        // 第一个用例: hmac.new(b"sample_token", b"sample_password", md5).hexdigest()
        assertEquals(
            "ebf4b9558b17e165c641ff3678d4ddb2",
            SrunCrypto.getMd5("sample_password", "sample_token")
        )
        // 第二个用例: 使用通用占位符,验证长 token + 长 password 的 HMAC-MD5 输出
        assertEquals(
            "348bfb651201728488d56f6eabcb3de8",
            SrunCrypto.getMd5("placeholder_password", "***")
        )
    }

    @Test
    fun `getSha1 matches Python hashlib sha1`() {
        assertEquals(
            "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3",
            SrunCrypto.getSha1("test")
        )
    }

    @Test
    fun `buildInfo full flow matches Python`() {
        // 端到端: username + placeholder_password + 占位 ip + 占位 token
        val info = SrunCrypto.buildInfo(
            username = "testuser",
            password = "placeholder_password",
            ip = "10.0.0.1",
            acId = "1",
            token = "***"
        )
        assertEquals(
            "{SRBX1}HzApoUgJXgbMhlrGRfjRUGCBoiWfQqUE4pGJAXU8yBxa/AWBAwI6hadIHs3QyCGNuOUVNDXmy54qBcEm1j6jSu7jTy78h0Ae7zRyxjieoQnwuwIzD/SjJ6fVJh+bHIYSfRHHXaSFJqQZAEy/N9WkRv==",
            info
        )
    }

    @Test
    fun `buildChksum full flow matches Python`() {
        val info = SrunCrypto.buildInfo(
            username = "testuser",
            password = "placeholder_password",
            ip = "10.0.0.1",
            acId = "1",
            token = "***"
        )
        val hmd5 = SrunCrypto.getMd5("placeholder_password", "***")
        val chksum = SrunCrypto.buildChksum(
            token = "***",
            username = "testuser",
            hmd5 = hmd5,
            acId = "1",
            ip = "10.0.0.1",
            iEnc = info
        )
        assertEquals("7a492c62cebb1616096c4e4272e9cb077e2e3d86", chksum)
    }
}
