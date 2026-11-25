// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 使用可推进的单调时钟和受控并发覆盖降级、五分钟冷却与旧结果隔离。 */
class PlaybackAlgorithmDynamicTest {
    private val now = AtomicLong(1_000L)
    private fun policy() = PlaybackAlgorithmPolicy(
        PlaybackAlgorithm.FRIDA,
        localFactory = { FakeSigner() },
        unidbgFactory = { FakeSigner() },
        fridaFactory = { FakeSigner() },
        automatic = true,
        clock = now::get,
        recoveryPause = {},
    )

    private fun degrade(policy: PlaybackAlgorithmPolicy) {
        repeat(2) {
            assertFalse(policy.addressFailed(policy.beginAddress()))
            assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        }
        assertTrue(policy.addressFailed(policy.beginAddress()))
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
    }

    @Test fun localIsAlwaysFirstAndSuccessfulRemoteIsReused() {
        val policy = policy()
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        degrade(policy)
        val remote = policy.beginAddress()
        policy.addressSucceeded(remote)
        repeat(3) {
            assertEquals(PlaybackAlgorithm.UNIDBG, policy.nextAddress(emptySet())?.algorithm)
            assertSame(remote.signer, policy.signer)
        }
        assertNull(policy.unavailable)
    }

    @Test fun localSuccessResetsFailuresAndEarlierConcurrentFailuresCannotUndoIt() {
        val policy = policy()
        repeat(2) { policy.addressFailed(policy.beginAddress()) }
        val old = policy.beginAddress()
        policy.addressSucceeded(policy.beginAddress())
        assertFalse(policy.addressFailed(old))
        degrade(policy)
    }

    @Test fun downgradeAndFailedProbeBothRequireFiveMinutesBeforeAnotherProbe() {
        val policy = policy()
        degrade(policy)
        now.addAndGet(299_999)
        assertNull(policy.recover { throw AssertionError("五分钟内不应探测本地") })
        now.incrementAndGet()
        requireNotNull(policy.recover { false }).get(2, TimeUnit.SECONDS)
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
        now.addAndGet(299_999)
        assertNull(policy.recover { throw AssertionError("探测失败后必须重新冷却五分钟") })
        now.incrementAndGet()
        requireNotNull(policy.recover { true }).get(2, TimeUnit.SECONDS)
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
    }

    @Test fun concurrentRequestsShareOneRecoveryAndLocalSuccessWinsOverRemoteDowngrade() {
        val policy = policy()
        degrade(policy)
        now.addAndGet(300_000)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val future = requireNotNull(policy.recover {
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            true
        })
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(20) { assertNull(policy.recover { throw AssertionError("重复探测") }) }
            val remote = policy.beginAddress()
            assertEquals(PlaybackAlgorithm.UNIDBG, remote.algorithm)
            policy.addressFailed(remote)
            assertEquals(PlaybackAlgorithm.FRIDA, policy.algorithm)
        } finally {
            release.countDown()
        }
        future.get(2, TimeUnit.SECONDS)
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
    }

    @Test fun disablingAutomaticModeIgnoresTheRunningRecoveryResult() {
        val policy = policy()
        degrade(policy)
        now.addAndGet(300_000)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val future = requireNotNull(policy.recover {
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            true
        })
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            policy.select(PlaybackAlgorithm.FRIDA)
        } finally {
            release.countDown()
        }
        future.get(2, TimeUnit.SECONDS)
        assertFalse(policy.isAutomatic)
        assertEquals(PlaybackAlgorithm.FRIDA, policy.algorithm)
        assertNull(policy.recover { throw AssertionError("手动模式不应探测") })
    }

    @Test fun remoteFailureFallsBackToFridaAndLateRemoteResultsDoNotOverrideLocalRecovery() {
        val policy = policy()
        degrade(policy)
        val stale = policy.beginAddress()
        policy.addressFailed(stale)
        assertEquals(PlaybackAlgorithm.FRIDA, policy.algorithm)
        now.addAndGet(300_000)
        requireNotNull(policy.recover { true }).get(2, TimeUnit.SECONDS)
        policy.addressFailed(stale)
        policy.addressSucceeded(stale)
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertNull(policy.unavailable)
    }

    @Test fun recoveryRetriesAfterTwoAndFourSecondsThenStartsFiveMinuteCooldown() {
        val delays = mutableListOf<Long>()
        val policy = PlaybackAlgorithmPolicy(
            PlaybackAlgorithm.LOCAL,
            automatic = true,
            clock = now::get,
            recoveryPause = { delays += it },
        )
        degrade(policy)
        now.addAndGet(300_000)
        var probes = 0
        requireNotNull(policy.recover { probes++; false }).get(2, TimeUnit.SECONDS)
        assertEquals(3, probes)
        assertEquals(listOf(2_000L, 4_000L), delays)
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
        now.addAndGet(299_999)
        assertNull(policy.recover { throw AssertionError("整轮失败后应等待五分钟") })
        now.incrementAndGet()
        probes = 0
        delays.clear()
        requireNotNull(policy.recover { ++probes == 2 }).get(2, TimeUnit.SECONDS)
        assertEquals(2, probes)
        assertEquals(listOf(2_000L), delays)
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
    }

}
