// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAlgorithmPolicyTest {
    @Test fun localSwitchesOnlyAfterThreeConsecutiveAddressFailures() {
        val changes = mutableListOf<PlaybackAlgorithmChange>()
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL, onChange = { changes += it })
        val first = policy.beginAddress()
        val second = policy.beginAddress()
        val third = policy.beginAddress()
        assertFalse(policy.addressFailed(first))
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertFalse(policy.addressFailed(second))
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertTrue(policy.addressFailed(third))
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
        assertEquals(1, changes.size)
    }

    @Test fun successfulAddressResetsConsecutiveFailureCount() {
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL)
        assertFalse(policy.addressFailed(policy.beginAddress()))
        policy.addressSucceeded(policy.beginAddress())
        assertFalse(policy.addressFailed(policy.beginAddress()))
        assertFalse(policy.addressFailed(policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertTrue(policy.addressFailed(policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
    }

    @Test fun remoteSwitchesToLocalOnlyAfterThreeConsecutiveFailures() {
        val changes = mutableListOf<PlaybackAlgorithmChange>()
        val policy = PlaybackAlgorithmPolicy(
            PlaybackAlgorithm.UNIDBG,
            onChange = { changes += it },
        )
        assertFalse(policy.addressFailed(policy.beginAddress()))
        assertFalse(policy.addressFailed(policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
        assertTrue(policy.addressFailed(policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertEquals(1, changes.size)
    }

    @Test fun successfulAddressDoesNotSwitchAlgorithm() {
        val policy = PlaybackAlgorithmPolicy(
            PlaybackAlgorithm.UNIDBG
        )
        policy.addressSucceeded(policy.beginAddress())
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
    }

    @Test fun oldAttemptAfterSwitchDoesNotAffectNewAlgorithm() {
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL)
        val old = policy.beginAddress()
        repeat(3) { assertFalse(policy.addressFailed(policy.beginAddress())) }
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
        assertFalse(policy.addressFailed(old))
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
    }
}
