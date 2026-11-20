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

/**
 * 播放取址算法策略。
 *
 * - 本地连续两次取址失败时切到 unidbg；
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

    /** 当前实际用于播放取址的签名器。 */
    val signer: RemoteSigner get() = synchronized(lock) { current }
    val algorithm: PlaybackAlgorithm? get() = synchronized(lock) { selected }

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

    /** 记录一次实际取址失败；返回本次是否自动切换到了 unidbg。 */
    fun addressFailed(): Boolean {
        val changed = synchronized(lock) {
            if (selected != PlaybackAlgorithm.LOCAL) return false
            failureCount++
            if (failureCount < LOCAL_FAILURE_LIMIT) return false
            failureCount = 0
            selected = PlaybackAlgorithm.UNIDBG
            current = create(PlaybackAlgorithm.UNIDBG)
            true
        }
        if (changed) {
            notify(PlaybackAlgorithmChange(
                PlaybackAlgorithm.LOCAL,
                PlaybackAlgorithm.UNIDBG,
                "本地算法连续 $LOCAL_FAILURE_LIMIT 次取址失败",
            ))
        }
        return changed
    }

    fun addressSucceeded() {
        synchronized(lock) { failureCount = 0 }
    }

    /** 设置页显式切换；相同算法不重复创建签名器。 */
    fun select(next: PlaybackAlgorithm) {
        val from = synchronized(lock) {
            if (selected == next) return
            val old = selected
            selected = next
            current = create(next)
            failureCount = 0
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
    }
}
