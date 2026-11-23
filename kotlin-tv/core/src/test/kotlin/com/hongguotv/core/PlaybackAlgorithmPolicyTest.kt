// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAlgorithmPolicyTest {
    @Test fun everyModeNotifiesAfterThreeFailuresWithoutSwitching() {
        for (algorithm in PlaybackAlgorithm.values()) {
            val changes = mutableListOf<PlaybackAlgorithmChange>()
            val failures = mutableListOf<PlaybackAlgorithmUnavailable>()
            val policy = PlaybackAlgorithmPolicy(algorithm,
                onChange = { changes += it }, onUnavailable = { failures += it })
            val signer = policy.signer
            repeat(2) { assertFalse(policy.addressFailed(policy.beginAddress())) }
            assertNull(policy.unavailable)
            assertTrue(policy.addressFailed(policy.beginAddress()))
            assertEquals(algorithm, policy.algorithm)
            assertSame(signer, policy.signer)
            assertEquals(algorithm, failures.single().algorithm)
            assertSame(failures.single(), policy.unavailable)
            assertTrue(changes.isEmpty())
            // 同一段连续失败只提示一次，避免并发预取反复打开对话框。
            repeat(5) { assertFalse(policy.addressFailed(policy.beginAddress())) }
            assertEquals(1, failures.size)
        }
    }

    @Test fun successfulAddressResetsConsecutiveFailureCount() {
        val failures = mutableListOf<PlaybackAlgorithmUnavailable>()
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL, onUnavailable = { failures += it })
        repeat(2) { assertFalse(policy.addressFailed(policy.beginAddress())) }
        policy.addressSucceeded(policy.beginAddress())
        repeat(2) { assertFalse(policy.addressFailed(policy.beginAddress())) }
        assertTrue(policy.addressFailed(policy.beginAddress()))
        policy.addressSucceeded(policy.beginAddress())
        assertNull(policy.unavailable)
        repeat(2) { assertFalse(policy.addressFailed(policy.beginAddress())) }
        assertTrue(policy.addressFailed(policy.beginAddress()))
        assertEquals(2, failures.size)
        assertFalse(failures[0] === failures[1])
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
    }

    @Test fun manualSelectionResetsFailuresAndNotifiesChange() {
        val changes = mutableListOf<PlaybackAlgorithmChange>()
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL, onChange = { changes += it })
        repeat(3) { policy.addressFailed(policy.beginAddress()) }
        policy.select(PlaybackAlgorithm.FRIDA)
        assertNull(policy.unavailable)
        assertEquals(PlaybackAlgorithm.FRIDA, policy.algorithm)
        assertEquals(PlaybackAlgorithm.FRIDA, changes.single().to)
        repeat(2) { assertFalse(policy.addressFailed(policy.beginAddress())) }
        assertTrue(policy.addressFailed(policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.FRIDA, policy.algorithm)
    }

    @Test fun staleResultsAfterManualSwitchAreIgnored() {
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL)
        val stale = policy.beginAddress()
        repeat(2) { assertFalse(policy.addressFailed(policy.beginAddress())) }
        policy.select(PlaybackAlgorithm.UNIDBG)
        policy.select(PlaybackAlgorithm.LOCAL)
        // 即使切回相同模式，旧请求也不能提前触发新模式的失败提示。
        assertFalse(policy.addressFailed(stale))
        repeat(2) { assertFalse(policy.addressFailed(policy.beginAddress())) }
        policy.addressSucceeded(stale)
        assertTrue(policy.addressFailed(policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
    }

    @Test fun sameModeSelectionKeepsSignerAndFailureCount() {
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL)
        val signer = policy.signer
        repeat(2) { assertFalse(policy.addressFailed(policy.beginAddress())) }
        policy.select(PlaybackAlgorithm.LOCAL)
        assertSame(signer, policy.signer)
        assertTrue(policy.addressFailed(policy.beginAddress()))
    }
}
