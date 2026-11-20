// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHeartbeatTest {
    private class FakeTransport : HeartbeatTransport {
        val requests=mutableListOf<Pair<String,String>>()
        var response=204
        override fun post(url: String, body: String): Int {
            requests += url to body
            return response
        }
    }

    @Test
    fun heartbeatUsesDeviceIdAndVersionCode() {
        val transport=FakeTransport()
        val client=DeviceHeartbeatClient("https://stats.example.com", transport)
        assertTrue(client.heartbeat("abcdef0123456789", "1.2.1", 1201))
        assertEquals("https://stats.example.com/api/v1/heartbeat", transport.requests.single().first)
        assertTrue(transport.requests.single().second.contains("\"install_id\":\"abcdef0123456789\""))
        assertTrue(transport.requests.single().second.contains("\"version_code\":1201"))
        assertTrue(transport.requests.single().second.contains("\"platform\":\"android\""))
    }

    @Test
    fun startAcceptsCreatedAndRejectsInvalidId() {
        val transport=FakeTransport().apply { response=201 }
        val client=DeviceHeartbeatClient("https://stats.example.com", transport)
        assertTrue(client.start("abcdef0123456789", "1.2.1", 1201))
        assertFalse(client.start("bad", "1.2.1", 1201))
    }

    @Test
    fun serverAndTransportFailuresAreSilent() {
        val rejected=FakeTransport().apply { response=500 }
        val client=DeviceHeartbeatClient("https://stats.example.com", rejected)
        assertFalse(client.heartbeat("abcdef0123456789", "1.2.1", 1201))

        val offline=DeviceHeartbeatClient("https://stats.example.com") { _, _ ->
            throw java.io.IOException("offline")
        }
        assertFalse(offline.heartbeat("abcdef0123456789", "1.2.1", 1201))
    }
}
