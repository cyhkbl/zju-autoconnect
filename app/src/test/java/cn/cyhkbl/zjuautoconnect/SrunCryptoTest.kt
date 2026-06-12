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
        // hmac.new(b"token123", b"password", md5).hexdigest()
        assertEquals(
            "ebf4b9558b17e165c641ff3678d4ddb2",
            SrunCrypto.getMd5("password", "token123")
        )
        assertEquals(
            "0f59f5d6a162593e9f602c5dc31f0da9",
            SrunCrypto.getMd5("cyh20070104", "deadbeef1234567890abcdef")
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
        val info = SrunCrypto.buildInfo(
            username = "u",
            password = "p",
            ip = "1.2.3.4",
            acId = "1",
            token = "abcdef0123456789"
        )
        assertEquals(
            "{SRBX1}Jzrfv3gwBYx0iZZhQ9qx5/YTGg+TZ5obBRZjQxwWzY05wb4JXl2fas2wIJn6klOtmFj8OB1DcpF9Bm8YckknQunCbQzMJVzXUg0JHBAL5Q/ccEK3",
            info
        )
    }

    @Test
    fun `buildChksum full flow matches Python`() {
        val info = SrunCrypto.buildInfo(
            username = "u",
            password = "p",
            ip = "1.2.3.4",
            acId = "1",
            token = "abcdef0123456789"
        )
        val hmd5 = SrunCrypto.getMd5("p", "abcdef0123456789")
        val chksum = SrunCrypto.buildChksum(
            token = "abcdef0123456789",
            username = "u",
            hmd5 = hmd5,
            acId = "1",
            ip = "1.2.3.4",
            iEnc = info
        )
        assertEquals("a50b3b9ed239c9f129c8ca70cdd7b161aa181ebc", chksum)
    }
}
