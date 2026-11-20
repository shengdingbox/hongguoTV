// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateTest {
    @Test
    fun newerVersionRequiresApkUrl() {
        val checker = AppUpdateChecker("https://update.example.com") { url ->
            assertEquals("https://update.example.com/api/v1/update?versionCode=1198&platform=android", url)
            """{"available":true,"current":{"version_name":"1.2.1","version_code":1201},"latest":{"version_name":"1.2.1","version_code":1201,"apk_url":"https://update.example.com/api/v1/releases/1201/apk"}}"""
        }
        val update = checker.check(1198)!!
        assertEquals("1.2.1", update.latestVersionName)
        assertEquals(1201L, update.latestVersionCode)
        assertEquals("https://update.example.com/api/v1/releases/1201/apk", update.apkUrl)
    }

    @Test
    fun sameVersionWithStaleAvailableFlagDoesNotUpgrade() {
        val checker = AppUpdateChecker("https://update.example.com") {
            """{"available":true,"current":{"version_name":"1.2.1","version_code":1201},"latest":{"version_name":"1.2.1","version_code":1201,"apk_url":"https://update.example.com/a.apk"}}"""
        }
        assertNull(checker.check(1201))
    }

    @Test
    fun sameVersionDoesNotUpgrade() {
        val checker = AppUpdateChecker("https://update.example.com") {
            """{"available":false}"""
        }
        assertNull(checker.check(1201))
    }
}
