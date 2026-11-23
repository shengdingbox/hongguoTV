// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** 播放恢复会话：域名健康度、空响应轮次与匿名身份轮换冷却跨子仓库共享。 */
class PlaybackRecovery {
    val routes = PlaybackRoutes()
    private val lock = Any()
    private var rotated = false
    private var rotatedAt = 0L
    private var rotationSucceeded: Boolean? = null
    private var emptyRounds = 0
    private var lastRoundWasEmpty = false

    fun markEmptyRound() {
        synchronized(lock) {
            emptyRounds++
            lastRoundWasEmpty = true
        }
    }

    fun markNonEmptyRound() {
        synchronized(lock) {
            emptyRounds = 0
            lastRoundWasEmpty = false
        }
    }

    fun lastEmptyRound(): Boolean = synchronized(lock) { lastRoundWasEmpty }

    fun emptyRoundsAtLeast(value: Int): Boolean = synchronized(lock) { emptyRounds >= value }

    fun resetEmptyRounds() {
        synchronized(lock) {
            emptyRounds = 0
            lastRoundWasEmpty = false
        }
    }

    fun allowsRotation(now: Long): Boolean = synchronized(lock) {
        val cooldown = if (rotationSucceeded == false) FAILED_ROTATION_COOLDOWN_MS else SUCCESS_ROTATION_COOLDOWN_MS
        if (rotated && now - rotatedAt < cooldown) false else {
            rotated = true
            rotatedAt = now
            true
        }
    }

    fun recordRotation(succeeded: Boolean) {
        synchronized(lock) { rotationSucceeded = succeeded }
    }

    fun resetRotationCooldown() {
        synchronized(lock) {
            rotated = false
            rotatedAt = 0L
            rotationSucceeded = null
            routes.reset()
        }
    }

    private companion object {
        const val FAILED_ROTATION_COOLDOWN_MS = 5_000L
        const val SUCCESS_ROTATION_COOLDOWN_MS = 30_000L
    }
}
