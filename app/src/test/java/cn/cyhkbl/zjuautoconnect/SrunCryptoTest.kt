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
        assertEquals("10d188dc61522d85", actualHex)
    }

    @Test
    fun `getXencode multi-byte string matches Python`() {
        val actual = SrunCrypto.getXencode("hello world", "foobar")
        val actualHex = actual.toByteArray(Charsets.ISO_8859_1)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
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
        assertEquals(
            "974e9b864986be83ca4d3ddee78f9dbd",
            SrunCrypto.getMd5("aa", "bb")
        )
        assertEquals(
            "6161fd740a74bf92dcb0090499b1799f",
            SrunCrypto.getMd5("aaa", "bbb")
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
        // Kotlin 实际构造的 i_str 包含 password 字段,Python 端直接传字面值算
        val info = SrunCrypto.buildInfo(
            username = "stu_a1b2c3d4",
            password = "a3f8b2e1d4c5e6f7",
            ip = "10.20.30.40",
            acId = "3",
            token = "***"
        )
        assertEquals(
            "{SRBX1}6ZI1RZpIBW9AdZB/oN1OXb4PQHX7ZXllb2/9+Gs8SbLHRzhAzxYC1CURdOLR8vpWowXJVuQSQKJkUdKjgSmT0FtYWCUprFwB1GiNTS9Y00W9a+FOQ8MWNbhA8oJKMRxcagBy2V+UenVq5mGZRMJxI+==",
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
        assertEquals("0f2bf77070a9b0253cb9722ba6554731c22681c1", chksum)
    }
}
