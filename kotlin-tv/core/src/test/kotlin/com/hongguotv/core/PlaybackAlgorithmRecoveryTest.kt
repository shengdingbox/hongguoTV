// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAlgorithmRecoveryTest {
    @Test fun thirdFailureReportsSwitchForPlaybackRetry() {
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL)
        assertFalse(policy.addressFailed(policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertFalse(policy.addressFailed(policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertTrue(policy.addressFailed(policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
    }
}
