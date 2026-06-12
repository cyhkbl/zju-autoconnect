package cn.cyhkbl.zjuautoconnect

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 单元测试 — 与 Python internet.pyw 的输出一致
 *
 * 注意:input 一律用不触发 redaction 的普通字面值(无 password/secret 等敏感词)
 */
class SrunCryptoTest {

    @Test
    fun `getXencode empty string returns empty`() {
        assertEquals("", SrunCrypto.getXencode("", "abc"))
    }

    @Test
    fun `getXencode ascii matches Python`() {
        val actual = SrunCrypto.getXencode("a", "k")
        val actualHex = actual.toByteArray(Charsets.ISO_8859_1)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        println("DEBUG getXencode(a, k) actual hex = $actualHex")
        assertEquals("10d188dc61522d85", actualHex)
    }

    @Test
    fun `getXencode multi-byte string matches Python`() {
        val actual = SrunCrypto.getXencode("hello world", "foobar")
        val actualHex = actual.toByteArray(Charsets.ISO_8859_1)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        println("DEBUG getXencode(hello world, foobar) actual hex = $actualHex")
        assertEquals("d68edf8f163d8d8f08736f9d111eb18c", actualHex)
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
        val v1 = SrunCrypto.getMd5("aa", "bb")
        println("DEBUG getMd5(aa, bb) actual = $v1")
        assertEquals("974e9b864986be83ca4d3ddee78f9dbd", v1)

        val v2 = SrunCrypto.getMd5("aaa", "bbb")
        println("DEBUG getMd5(aaa, bbb) actual = $v2")
        assertEquals("6161fd740a74bf92dcb0090499b1799f", v2)
    }

    @Test
    fun `getSha1 matches Python hashlib sha1`() {
        val v = SrunCrypto.getSha1("test")
        println("DEBUG getSha1(test) actual = $v")
        assertEquals("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3", v)
    }

    @Test
    fun `buildInfo full flow matches Python`() {
        val info = SrunCrypto.buildInfo(
            username = "stu_a1b2c3d4",
            password = "a3f8b2e1d4c5e6f7",
            ip = "10.20.30.40",
            acId = "3",
            token = "***"
        )
        println("DEBUG buildInfo actual = $info")
        println("DEBUG buildInfo actual hex = ${info.toByteArray(Charsets.ISO_8859_1).joinToString("") { "%02x".format(it.toInt() and 0xFF) }}")
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
        println("DEBUG buildChksum actual = $chksum")
        assertEquals("3d23e29eef4d15923970c101374bd24e7e6cc106", chksum)
    }
}
