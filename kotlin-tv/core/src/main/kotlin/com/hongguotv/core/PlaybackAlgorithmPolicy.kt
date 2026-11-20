// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** 当前播放算法，与设置页的三种算法模式一一对应。 */
enum class PlaybackAlgorithm {
    LOCAL,
    UNIDBG,
    FRIDA,
}

/** 自动算法切换通知；界面据此同步设置并提示用户。 */
data class PlaybackAlgorithmChange(
    val from: PlaybackAlgorithm?,
    val to: PlaybackAlgorithm,
    val reason: String,
)

/** 一次取址开始时绑定的算法与选择版本；迟到的结果不能影响已经切换后的新算法。 */
data class PlaybackAlgorithmAttempt internal constructor(
    val algorithm: PlaybackAlgorithm?,
    internal val revision: Long,
)

/**
 * 播放取址算法策略。
 *
 * - 当前算法连续三次取址失败时才自动切换；
 * - 本地算法失败后切到 unidbg，远程算法失败后切回本地；
 * - 成功完成的取址会重置连续失败计数；
 * - 旧算法请求的迟到结果会因选择版本过期而忽略，避免把失败记到新算法上。
 */
class PlaybackAlgorithmPolicy(
    initial: PlaybackAlgorithm?,
    private val localFactory: () -> RemoteSigner = { RemoteSigner() },
    private val unidbgFactory: () -> RemoteSigner = { UnidbgSigner() },
    private val fridaFactory: () -> RemoteSigner = { FridaSigner() },
    private val onChange: ((PlaybackAlgorithmChange) -> Unit)? = null,
) {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(PlaybackAlgorithmChange) -> Unit>().apply {
        onChange?.let(::add)
    }

    private val lock = Any()
    private var selected = initial
    private var current = create(initial)
    private var failureCount = 0
    private var revision = 0L

    /** 当前实际用于播放取址的签名器。 */
    val signer: RemoteSigner get() = synchronized(lock) { current }
    val algorithm: PlaybackAlgorithm? get() = synchronized(lock) { selected }

    /** 在真正发起取址前记录当前算法；结果回传时据此过滤切换前发出的旧请求。 */
    fun beginAddress(): PlaybackAlgorithmAttempt = synchronized(lock) {
        PlaybackAlgorithmAttempt(selected, revision)
    }

    /** 记录一次实际取址失败；同一算法连续三次失败后切到对应的备用算法。 */
    fun addressFailed(attempt: PlaybackAlgorithmAttempt): Boolean {
        val changed = synchronized(lock) {
            if (attempt.revision != revision || attempt.algorithm != selected) return false
            val from = selected ?: return false
            failureCount++
            if (failureCount < FAILURE_LIMIT) return false
            val next = fallbackFor(from)
            failureCount = 0
            selected = next
            current = create(next)
            revision++
            from to next
        }
        if (changed != null) {
            notify(PlaybackAlgorithmChange(
                changed.first,
                changed.second,
                "${algorithmLabel(changed.first)}连续 $FAILURE_LIMIT 次取址失败",
            ))
        }
        return changed != null
    }

    fun addressSucceeded(attempt: PlaybackAlgorithmAttempt) {
        synchronized(lock) {
            if (attempt.revision == revision && attempt.algorithm == selected) failureCount = 0
        }
    }

    /** 设置页显式切换；相同算法不重复创建签名器。 */
    fun select(next: PlaybackAlgorithm) {
        val from = synchronized(lock) {
            if (selected == next) return
            val old = selected
            selected = next
            current = create(next)
            failureCount = 0
            revision++
            old
        }
        notify(PlaybackAlgorithmChange(from, next, "用户选择"))
    }

    fun addListener(listener: (PlaybackAlgorithmChange) -> Unit) { if (listener !in listeners) listeners += listener }
    fun removeListener(listener: (PlaybackAlgorithmChange) -> Unit) { listeners -= listener }

    private fun notify(change: PlaybackAlgorithmChange) {
        listeners.forEach { runCatching { it(change) } }
    }

    private fun create(algorithm: PlaybackAlgorithm?): RemoteSigner = when (algorithm) {
        PlaybackAlgorithm.LOCAL -> localFactory()
        PlaybackAlgorithm.UNIDBG -> unidbgFactory()
        PlaybackAlgorithm.FRIDA -> fridaFactory()
        null -> RemoteSigner()
    }

    private fun fallbackFor(algorithm: PlaybackAlgorithm): PlaybackAlgorithm = when (algorithm) {
        PlaybackAlgorithm.LOCAL -> PlaybackAlgorithm.UNIDBG
        PlaybackAlgorithm.UNIDBG, PlaybackAlgorithm.FRIDA -> PlaybackAlgorithm.LOCAL
    }

    private fun algorithmLabel(algorithm: PlaybackAlgorithm): String = when (algorithm) {
        PlaybackAlgorithm.LOCAL -> "本地算法"
        PlaybackAlgorithm.UNIDBG -> "远程计算-unidbg"
        PlaybackAlgorithm.FRIDA -> "远程计算-frida"
    }

    private companion object {
        const val FAILURE_LIMIT = 3
    }
}
