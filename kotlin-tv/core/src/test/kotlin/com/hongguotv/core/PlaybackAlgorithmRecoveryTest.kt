// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAlgorithmRecoveryTest {
    @Test fun thirdConcurrentFailureRequestsManualSelectionWithoutRetrySwitch() {
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL)
        val first = policy.beginAddress()
        val second = policy.beginAddress()
        val third = policy.beginAddress()
        assertFalse(policy.addressFailed(first))
        assertFalse(policy.addressFailed(second))
        assertTrue(policy.addressFailed(third))
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertEquals(PlaybackAlgorithm.LOCAL, policy.unavailable?.algorithm)
    }
}
