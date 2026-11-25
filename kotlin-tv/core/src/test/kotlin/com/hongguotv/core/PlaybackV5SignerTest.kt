// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackV5SignerTest {
    @Test fun signingAddsOneTimestampSharedByQueryAndKhronos() {
        val now = 1_700_000_000_000L
        val signed = PlaybackV5Signer.shared.sign(
            "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/v1/",
            linkedMapOf(
                "device_id" to "7000000000000000001",
                "iid" to "7000000000000000002",
                "version_name" to "7.4.3.32",
                "os_version" to "14",
                "device_type" to "23127PN0CC",
                "channel" to "xiaomi_8662_64",
            ),
            """{"video_id":"123"}""".toByteArray(),
            now,
        )

        val queryTs = signed.url.substringAfter('?').split('&')
            .map { it.substringBefore('=') to it.substringAfter('=') }
            .toMap()["ts"]
        assertEquals("1700000000", queryTs)
        assertEquals(queryTs, signed.headers["x-khronos"])
        assertTrue(signed.headers.getValue("x-medusa").isNotBlank())
        assertTrue(signed.headers.getValue("x-helios").isNotBlank())
    }
}
