// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProbeTest {
    private val profile = SignerProfile(
        linkedMapOf(
            "device_id" to "device", "iid" to "install", "cdid" to "cdid",
            "channel" to "channel", "resolution" to "1080*2400",
        ),
        "agent",
    )

    @Test fun signatureInputMatchesPlaybackVideoTabValues() {
        val values = PlaybackProbe.signatureValues("123", profile)
        assertEquals("123", values["video_id"])
        assertEquals("1", values["device_level"])
        assertEquals("1024", values["video_platform"])
        assertEquals("device", values["device_id"])
        assertEquals("install", values["iid"])
        assertEquals("cdid", values["cdid"])
        assertTrue(values["version_name"] == "7.4.3.32")
    }
}
