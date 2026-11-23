// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAlgorithmPolicyTest {
    @Test fun localSwitchesOnlyAfterThreeConsecutiveAlgorithmicFailures() {
        val changes = mutableListOf<PlaybackAlgorithmChange>()
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL, onChange = { changes += it })
        assertFalse(policy.addressFailed(attempt = policy.beginAddress()))
        assertFalse(policy.addressFailed(attempt = policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertTrue(policy.addressFailed(attempt = policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
        assertEquals(1, changes.size)
    }

    @Test fun networkFailuresNeverCountNorSwitch() {
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL)
        repeat(10) {
            assertFalse(policy.addressFailed(attempt = policy.beginAddress(), network = true))
        }
        // 网络失败不计入连续失败：随后的算法失败仍需满三次才切换。
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertFalse(policy.addressFailed(attempt = policy.beginAddress()))
        assertFalse(policy.addressFailed(attempt = policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertTrue(policy.addressFailed(attempt = policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
    }

    @Test fun successfulAddressResetsConsecutiveFailureCount() {
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL)
        assertFalse(policy.addressFailed(attempt = policy.beginAddress()))
        policy.addressSucceeded(policy.beginAddress())
        assertFalse(policy.addressFailed(attempt = policy.beginAddress()))
        assertFalse(policy.addressFailed(attempt = policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertTrue(policy.addressFailed(attempt = policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
    }

    @Test fun remoteFailureDoesNotBlindSwitchBackToLocal() {
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.UNIDBG)
        repeat(5) {
            assertFalse(policy.addressFailed(attempt = policy.beginAddress()))
            assertFalse(policy.addressFailed(attempt = policy.beginAddress(), network = true))
        }
        // 远程失败不自动切回本地：本地可用性由探测决定，避免两边都坏时来回震荡。
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
    }

    @Test fun staleAttemptAfterManualSwitchIsIgnored() {
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL)
        val stale = policy.beginAddress()
        assertFalse(policy.addressFailed(attempt = policy.beginAddress()))
        assertFalse(policy.addressFailed(attempt = policy.beginAddress()))
        policy.select(PlaybackAlgorithm.UNIDBG)
        policy.select(PlaybackAlgorithm.LOCAL)
        // 切换前发出的旧请求失败不得计入新选择，否则会被提前降级。
        assertFalse(policy.addressFailed(attempt = stale))
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertFalse(policy.addressFailed(attempt = policy.beginAddress()))
        assertFalse(policy.addressFailed(attempt = policy.beginAddress()))
        assertTrue(policy.addressFailed(attempt = policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
    }

    @Test fun probeSwitchesBackOnlyAfterTwoConsecutiveSuccesses() {
        val changes = mutableListOf<PlaybackAlgorithmChange>()
        val policy = PlaybackAlgorithmPolicy(
            PlaybackAlgorithm.UNIDBG,
            localProbeFactory = { object : RemoteSigner() {} },
            onChange = { changes += it },
        )
        assertTrue(policy.needsLocalProbe())
        assertTrue(policy.probeLocal { true })
        policy.localProbeSucceeded()
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
        assertTrue(policy.probeLocal { true })
        policy.localProbeSucceeded()
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertEquals(1, changes.size)
        assertFalse(policy.needsLocalProbe())
    }

    @Test fun probeFailureResetsConsecutiveSuccessCount() {
        val policy = PlaybackAlgorithmPolicy(
            PlaybackAlgorithm.UNIDBG,
            localProbeFactory = { object : RemoteSigner() {} },
        )
        assertTrue(policy.probeLocal { true })
        policy.localProbeSucceeded()
        assertFalse(policy.probeLocal { false })
        assertTrue(policy.probeLocal { true })
        policy.localProbeSucceeded()
        // 成功-失败-成功只算连续一次，尚未切回。
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
        assertTrue(policy.probeLocal { true })
        policy.localProbeSucceeded()
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
    }

    @Test fun failedProbeNeverSwitchesAlgorithm() {
        val policy = PlaybackAlgorithmPolicy(
            PlaybackAlgorithm.UNIDBG,
            localProbeFactory = { object : RemoteSigner() {} },
        )
        assertFalse(policy.probeLocal { false })
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
    }

    @Test fun staleSuccessfulProbeDoesNotOverrideManualSelection() {
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.FRIDA)
        val stale = policy.beginAddress()
        policy.localProbeSucceeded(attempt = stale)
        policy.select(PlaybackAlgorithm.UNIDBG)
        repeat(2) { policy.localProbeSucceeded(attempt = stale) }
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
    }

    @Test fun staleFailedProbeDoesNotEraseCurrentSuccessfulProbe() {
        val policy = PlaybackAlgorithmPolicy(
            PlaybackAlgorithm.FRIDA,
            localProbeFactory = { FakeSigner() },
        )
        val stale = policy.beginAddress()
        policy.select(PlaybackAlgorithm.UNIDBG)
        val current = policy.beginAddress()
        policy.localProbeSucceeded(attempt = current)
        assertFalse(policy.probeLocal(revision = stale) { false })
        policy.localProbeSucceeded(attempt = current)
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
    }

    @Test fun playbackFallbackOnlyAvailableUnderLocalAlgorithm() {
        val policy = PlaybackAlgorithmPolicy(
            PlaybackAlgorithm.LOCAL,
            unidbgFactory = { object : RemoteSigner() {} },
        )
        assertNotNull(policy.playbackFallbackSigner())
        policy.select(PlaybackAlgorithm.UNIDBG)
        assertNull(policy.playbackFallbackSigner())
    }
}
