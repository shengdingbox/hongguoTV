// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAlgorithmRecoveryTest {
    @Test fun thirdPlaybackFailureReportsSwitchForPlaybackRetry() {
        val policy = PlaybackAlgorithmPolicy(PlaybackAlgorithm.LOCAL)
        val first = policy.beginAddress()
        val second = policy.beginAddress()
        val third = policy.beginAddress()
        assertFalse(policy.addressFailed(playback = true, attempt = first))
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertFalse(policy.addressFailed(playback = true, attempt = second))
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertTrue(policy.addressFailed(playback = true, attempt = third))
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
    }
}
