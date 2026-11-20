// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAlgorithmPolicyTest {
    @Test fun localSwitchesOnlyAfterTwoConsecutiveAddressFailures() {
        val changes = mutableListOf<PlaybackAlgorithmChange>()
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL, onChange = { changes += it })
        assertFalse(policy.addressFailed())
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertTrue(policy.addressFailed())
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
        assertEquals(1, changes.size)
    }

    @Test fun successfulAddressResetsConsecutiveFailureCount() {
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL)
        assertFalse(policy.addressFailed(attempt = policy.beginAddress()))
        policy.addressSucceeded(policy.beginAddress())
        assertFalse(policy.addressFailed(attempt = policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertTrue(policy.addressFailed(attempt = policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
    }

    @Test fun playbackSwitchesOnlyAfterThreeConsecutiveAddressFailures() {
        val changes = mutableListOf<PlaybackAlgorithmChange>()
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL, onChange = { changes += it })
        assertFalse(policy.addressFailed(playback = true, attempt = policy.beginAddress()))
        assertFalse(policy.addressFailed(playback = true, attempt = policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertTrue(policy.addressFailed(playback = true, attempt = policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
        assertEquals(1, changes.size)
    }

    @Test fun nonPlaybackAddressKeepsOriginalTwoFailureLimit() {
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL)
        assertFalse(policy.addressFailed(attempt = policy.beginAddress()))
        assertTrue(policy.addressFailed())
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
    }

    @Test fun oldAttemptAfterSwitchDoesNotResetNewAlgorithm() {
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL)
        val old = policy.beginAddress()
        assertFalse(policy.addressFailed(attempt = policy.beginAddress()))
        assertTrue(policy.addressFailed(attempt = policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
        policy.addressSucceeded(old)
        val first = policy.beginAddress()
        val second = policy.beginAddress()
        assertFalse(policy.addressFailed(attempt = first))
        assertFalse(policy.addressFailed(attempt = second))
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
        assertTrue(policy.addressFailed(attempt = policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
    }

    @Test fun staleFailureDoesNotCountTowardsNewAlgorithm() {
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL)
        val old = policy.beginAddress()
        assertFalse(policy.addressFailed(attempt = policy.beginAddress()))
        assertTrue(policy.addressFailed(attempt = policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
        assertFalse(policy.addressFailed(playback = true, attempt = old))
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
    }

    @Test fun successfulRemoteAddressAndLocalProbeSwitchesBackToLocal() {
        val changes = mutableListOf<PlaybackAlgorithmChange>()
        val policy = PlaybackAlgorithmPolicy(
            PlaybackAlgorithm.UNIDBG,
            localProbeFactory = { object : RemoteSigner() {} },
            onChange = { changes += it },
        )
        assertTrue(policy.needsLocalProbe())
        assertTrue(policy.probeLocal { true })
        policy.localProbeSucceeded()
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertEquals(1, changes.size)
        assertFalse(policy.needsLocalProbe())
    }

    @Test fun failedProbeNeverSwitchesAlgorithm() {
        val policy = PlaybackAlgorithmPolicy(
            PlaybackAlgorithm.UNIDBG,
            localProbeFactory = { object : RemoteSigner() {} },
        )
        assertFalse(policy.probeLocal { false })
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
    }
}
