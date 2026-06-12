package cn.cyhkbl.zjuautoconnect

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 单元测试 — 与 Python internet.pyw 的输出一致
 *
 * Expected 值通过运行 /home/henry/internet.pyw 算得。
 * 注意:input 一律用不触发 redaction 的普通字面值(无 password/secret 等敏感词)
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
        assertEquals("++==", SrunCrypto.getBase64("A"))
        assertEquals("+H2=", SrunCrypto.getBase64("AB"))
        assertEquals("+HRJ", SrunCrypto.getBase64("ABC"))
        assertEquals("+HRJhL==", SrunCrypto.getBase64("ABCD"))
        assertEquals("+HRJhPH=", SrunCrypto.getBase64("ABCDE"))
    }

    @Test
    fun `getMd5 matches Python hmac md5`() {
        // hmac.new(b"bb", b"aa", md5).hexdigest() = 974e9b864986be83ca4d3ddee78f9dbd
        assertEquals(
            "974e9b864986be83ca4d3ddee78f9dbd",
            SrunCrypto.getMd5("aa", "bb")
        )
        // hmac.new(b"bbb", b"aaa", md5).hexdigest() = 6161fd740a74bf92dcb0090499b1799f
        assertEquals(
            "6161fd740a74bf92dcb0090499b1799f",
            SrunCrypto.getMd5("aaa", "bbb")
        )
    }

    @Test
    fun `getSha1 matches Python hashlib sha1`() {
        // hashlib.sha1(b"test").hexdigest() = a94a8fe5ccb19ba61c4c0873d391e987982fbbd3
        assertEquals(
            "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3",
            SrunCrypto.getSha1("test")
        )
    }

    @Test
    fun `buildInfo full flow matches Python`() {
        // i_str 字段名是 srun 标准格式(包含 "password" 字段)
        // 端到端: i_str + xencode + base64 = "{SRBX1}..."
        val info = SrunCrypto.buildInfo(
            username = "stu_a1b2c3d4",
            password = "a3f8b2e1d4c5e6f7",
            ip = "10.20.30.40",
            acId = "3",
            token = "***"
        )
        assertEquals(
            "{SRBX1}cFiFD42WpvV9zgSDYn+3uPhfAmSwWs1ZJ17c1fO3hHhCNgYinSTMrklnim0wcG/Mj24G5WrYXJg/WQuOxW/sItVoJkijwiw1MNCvhlk/y9D5k0nLZw0uFH4I08zwXvnZ6DuCP0vffkyv5wXb2C/HTv==",
            info
        )
    }

    @Test
    fun `buildChksum full flow matches Python`() {
        val info = SrunCrypto.buildInfo(
            username = "stu_a1b2c3d4",
            password = "a3f8b2e1d4c5e6f7",
            ip = "10.20.30.40",
            acId = "3",
            token = "***"
        )
        val hmd5 = SrunCrypto.getMd5("a3f8b2e1d4c5e6f7", "***")
        val chksum = SrunCrypto.buildChksum(
            token = "***",
            username = "stu_a1b2c3d4",
            hmd5 = hmd5,
            acId = "3",
            ip = "10.20.30.40",
            iEnc = info
        )
        assertEquals("3d23e29eef4d15923970c101374bd24e7e6cc106", chksum)
    }
}
