// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRoutesTest {
    @Test fun prefersRecentSuccessAndAvoidsEmptyRoutesUntilCooldownEnds() {
        val routes = PlaybackRoutes()
        val initial = routes.plan(1000, false)
        assertEquals(3, initial.size)
        assertEquals(PlayRetryPlan.hosts, initial.map { it.host })

        routes.failed(initial[0], 1000, empty = true)
        routes.success(initial[2], 1000)

        val next = routes.plan(1001, false)
        assertEquals(initial[2], next.first())
        assertFalse(next.contains(initial[0]))
        assertTrue(routes.plan(31_001, false).contains(initial[0]))
    }

    @Test fun allEmptyRoutesExhaustSessionAndForegroundProbesOnlyOne() {
        val routes = PlaybackRoutes()
        val initial = routes.plan(1000, false)
        initial.forEach { routes.failed(it, 1000, empty = true) }

        assertTrue(routes.exhausted(1001))
        assertTrue(routes.plan(1001, speculative = true).isEmpty())
        assertEquals(1, routes.plan(1001, speculative = false).size)
    }

    @Test fun timeoutDoesNotMarkEveryRouteEmptyAndNewSessionStartsHealthy() {
        val routes = PlaybackRoutes()
        val initial = routes.plan(1000, false)
        initial.forEach { routes.failed(it, 1000, empty = true) }
        routes.failed(initial[1], 1000, empty = false)

        assertFalse(routes.exhausted(1001))
        assertEquals(3, PlaybackRoutes().plan(1001, false).size)
    }

    @Test fun recoveryRotationIsGatedByCooldownAndClearsOnlyAfterSuccess() {
        val recovery = PlaybackRecovery()
        assertTrue(recovery.allowsRotation(1000))
        assertFalse(recovery.allowsRotation(1001))
        recovery.recordRotation(false)
        assertFalse(recovery.allowsRotation(5000))
        assertTrue(recovery.allowsRotation(6001))
        recovery.resetRotationCooldown()
        assertTrue(recovery.allowsRotation(6002))
    }

    @Test fun identityRotationClearsOldRouteCooldown() {
        val recovery = PlaybackRecovery()
        val routes = recovery.routes.plan(1000, false)
        routes.forEach { recovery.routes.failed(it, 1000, empty = true) }
        assertTrue(recovery.routes.exhausted(1001))
        recovery.resetRotationCooldown()
        assertFalse(recovery.routes.exhausted(1001))
        assertEquals(3, recovery.routes.plan(1001, false).size)
    }

    @Test fun successMemorySeparatesAlbumEpisodesAndKeepsNewerConcurrentSuccess() {
        val memory = PlaybackRouteMemory()
        val signer = FakeSigner()
        memory.remember(signer, MobilePlayback.GROUP_SHORT, PlaybackRouteKind.PLAYER, "/single", 1_000L)
        val previous = memory.last(signer, MobilePlayback.GROUP_SHORT, 1_001L)!!
        assertNull(memory.last(signer, MobilePlayback.GROUP_PUGC, 1_001L))
        memory.remember(signer, MobilePlayback.GROUP_PUGC, PlaybackRouteKind.LEGACY, "/album", 1_001L)
        memory.remember(signer, MobilePlayback.GROUP_SHORT, PlaybackRouteKind.VIDEO_TAB_MULTI, "/multi", 1_002L)
        memory.failed(signer, MobilePlayback.GROUP_SHORT, previous)
        assertEquals("/multi", memory.last(signer, MobilePlayback.GROUP_SHORT, 1_003L)?.endpoint)
        assertEquals("/album", memory.last(signer, MobilePlayback.GROUP_PUGC, 1_003L)?.endpoint)
        val current = memory.last(signer, MobilePlayback.GROUP_SHORT, 1_003L)!!
        memory.failed(signer, MobilePlayback.GROUP_SHORT, current)
        assertNull(memory.last(signer, MobilePlayback.GROUP_SHORT, 1_004L))
        assertEquals("/album", memory.last(signer, MobilePlayback.GROUP_PUGC, 1_004L)?.endpoint)
    }

    @Test fun identityRotationAlsoClearsSuccessfulInterfaceMemory() {
        val recovery = PlaybackRecovery()
        val signer = FakeSigner()
        recovery.successfulRoutes.remember(signer, MobilePlayback.GROUP_SHORT, PlaybackRouteKind.PLAYER, "/single", 1_000L)
        recovery.resetRotationCooldown()
        assertNull(recovery.successfulRoutes.last(signer, MobilePlayback.GROUP_SHORT, 1_001L))
    }

    @Test fun onlyEmptyResponsesCountAsIdentityRotationRounds() {
        val recovery = PlaybackRecovery()
        recovery.markNonEmptyRound()
        assertFalse(recovery.lastEmptyRound())
        assertEquals(false, recovery.emptyRoundsAtLeast(1))
        recovery.markEmptyRound()
        assertTrue(recovery.lastEmptyRound())
        assertTrue(recovery.emptyRoundsAtLeast(1))
        recovery.resetEmptyRounds()
        assertFalse(recovery.lastEmptyRound())
        assertEquals(false, recovery.emptyRoundsAtLeast(1))
    }
}
