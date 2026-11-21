// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BytesTest {
    @Test fun base64MatchesStandardEncodingWithoutAndroidJavaApi() {
        assertEquals("", ByteArray(0).base64())
        assertEquals("Zg==", "f".toByteArray().base64())
        assertEquals("Zm8=", "fo".toByteArray().base64())
        assertEquals("Zm9v", "foo".toByteArray().base64())
        assertEquals("SGVsbG8sIOS4lueVjA==", "Hello, 世界".toByteArray().base64())
    }

    @Test fun decodeAcceptsStandardAndUrlSafeAlphabet() {
        assertArrayEquals("foobar".toByteArray(), decode64("Zm9vYmFy"))
        assertArrayEquals(byteArrayOf(-5, -1, 0, 1), decode64("-_8AAQ=="))
        assertArrayEquals(byteArrayOf(-5, -1, 0, 1), decode64("-_8AAQ"))
    }
}
