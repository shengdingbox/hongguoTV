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

/** 一次取址开始时绑定的算法版本；切换前发出的旧请求不能把结果记到新算法上。 */
data class PlaybackAlgorithmAttempt internal constructor(
    internal val revision: Long,
)

/**
 * 播放取址算法策略。
 *
 * - 本地连续两次取址失败时切到 unidbg，正式播放使用三次阈值；
 * - 远程连续两次取址失败时切回本地，正式播放使用三次阈值；
 * - 使用远程算法时并行探测本地，本地成功即切回；
 * - 探测使用独立设备档案副本，失败时的重新注册不会污染正在使用的身份。
 */
class PlaybackAlgorithmPolicy(
    initial: PlaybackAlgorithm?,
    private val localFactory: () -> RemoteSigner = { RemoteSigner() },
    private val localProbeFactory: () -> RemoteSigner = localFactory,
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
    private var playbackFailureCount = 0
    private var revision = 0L

    /** 当前实际用于播放取址的签名器。 */
    val signer: RemoteSigner get() = synchronized(lock) { current }
    val algorithm: PlaybackAlgorithm? get() = synchronized(lock) { selected }

    /** 在真正发起取址前记录当前选择版本。 */
    fun beginAddress(): PlaybackAlgorithmAttempt = synchronized(lock) {
        PlaybackAlgorithmAttempt(revision)
    }

    /** 只在远程模式需要并行探测本地。 */
    fun needsLocalProbe(): Boolean = synchronized(lock) {
        selected == PlaybackAlgorithm.UNIDBG || selected == PlaybackAlgorithm.FRIDA
    }

    /** 使用隔离的本地签名器试取一次；探测失败不得替换正式设备身份。 */
    fun probeLocal(attempt: (RemoteSigner) -> Boolean): Boolean =
        runCatching { attempt(localProbeFactory()) }.getOrDefault(false)

    /** 本地探测成功：从远程切回本地；当前请求继续完成，下一次取址立即生效。 */
    fun localProbeSucceeded(reason: String = "远程算法取址成功，本地算法探测成功") {
        switchTo(PlaybackAlgorithm.LOCAL, reason)
    }

    /**
     * 记录一次实际取址失败；返回本次是否自动切换到了备用算法。
     *
     * [playback] 只标记没有命中预取结果的正式播放加载。其他取址场景继续使用原来的两次阈值。
     */
    fun addressFailed(playback: Boolean = false, attempt: PlaybackAlgorithmAttempt? = null): Boolean {
        val changed = synchronized(lock) {
            if (attempt != null && attempt.revision != revision) return false
            val from = selected ?: return false
            val fallback = if (from == PlaybackAlgorithm.LOCAL) PlaybackAlgorithm.UNIDBG else PlaybackAlgorithm.LOCAL
            val limit = if (playback) PLAYBACK_FAILURE_LIMIT else LOCAL_FAILURE_LIMIT
            if (playback) playbackFailureCount++ else failureCount++
            val count = if (playback) playbackFailureCount else failureCount
            if (count < limit) return false
            failureCount = 0
            playbackFailureCount = 0
            selected = fallback
            current = create(fallback)
            revision++
            from to fallback
        }
        if (changed != null) {
            notify(PlaybackAlgorithmChange(
                changed.first,
                changed.second,
                "${if (changed.first == PlaybackAlgorithm.LOCAL) "本地算法" else "远程算法"}连续 ${if (playback) PLAYBACK_FAILURE_LIMIT else LOCAL_FAILURE_LIMIT} 次取址失败",
            ))
        }
        return changed != null
    }

    fun addressSucceeded(attempt: PlaybackAlgorithmAttempt? = null) {
        synchronized(lock) {
            if (attempt != null && attempt.revision != revision) return
            failureCount = 0
            playbackFailureCount = 0
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
            playbackFailureCount = 0
            revision++
            old
        }
        notify(PlaybackAlgorithmChange(from, next, "用户选择"))
    }

    private fun switchTo(next: PlaybackAlgorithm, reason: String) {
        val from = synchronized(lock) {
            if (selected == next) return
            val old = selected
            selected = next
            current = create(next)
            failureCount = 0
            playbackFailureCount = 0
            revision++
            old
        }
        notify(PlaybackAlgorithmChange(from, next, reason))
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

    private companion object {
        const val LOCAL_FAILURE_LIMIT = 2
        const val PLAYBACK_FAILURE_LIMIT = 3
    }
}
