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
 * 播放取址算法策略：本地优先，实在不行才降级远程。
 *
 * - 网络类失败（连接失败/超时）与算法无关，不计数不切换，避免网络抖动来回切；
 * - 本地算法类失败（空体/错误码）连续 [FAILURE_LIMIT] 次才切到 unidbg；
 * - 远程算法失败不自动切回本地——本地当初正是失败才被切走的，盲切只会来回震荡；
 * - 远程模式每次取址并行探测本地（实时嗅探），但需连续 [PROBE_SUCCESS_LIMIT] 次
 *   探测成功才切回，防止半坏的本地算法被偶发成功拉回；
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
    private var probeSuccessCount = 0
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

    /**
     * 正式播放的请求级兜底签名器：本地算法首次算法类失败时，不等切换阈值
     * 先用 unidbg 救当前这一次取址。只救本次请求，不改变全局选择。
     * 非本地模式没有更进一步的兜底，返回 null。
     */
    fun playbackFallbackSigner(): RemoteSigner? = synchronized(lock) {
        if (selected == PlaybackAlgorithm.LOCAL) unidbgFactory() else null
    }

    /** 使用隔离的本地签名器试取一次；探测失败不得替换正式设备身份，并清零连续成功计数。 */
    fun probeLocal(attempt: (RemoteSigner) -> Boolean): Boolean =
        runCatching { attempt(localProbeFactory()) }.getOrDefault(false).also { succeeded ->
            if (!succeeded) synchronized(lock) { probeSuccessCount = 0 }
        }

    /** 本地探测成功一次：连续 [PROBE_SUCCESS_LIMIT] 次成功才真正切回本地。 */
    fun localProbeSucceeded(reason: String = "本地算法连续 ${PROBE_SUCCESS_LIMIT} 次探测成功") {
        val ready = synchronized(lock) { ++probeSuccessCount >= PROBE_SUCCESS_LIMIT }
        if (ready) switchTo(PlaybackAlgorithm.LOCAL, reason)
    }

    /**
     * 记录一次实际取址失败；返回本次是否自动切换到了备用算法。
     *
     * - [network] 为 true 表示纯网络故障（连接失败/超时）：换算法无济于事（取址请求
     *   始终由本机发出），既不计数也不切换；
     * - 算法类失败只有本地会计数：远程失败不自动切回本地，本地可用性由探测决定。
     */
    fun addressFailed(attempt: PlaybackAlgorithmAttempt? = null, network: Boolean = false): Boolean {
        val changed = synchronized(lock) {
            if (attempt != null && attempt.revision != revision) return false
            if (network) return false
            if (selected != PlaybackAlgorithm.LOCAL) return false
            failureCount++
            if (failureCount < FAILURE_LIMIT) return false
            failureCount = 0
            selected = PlaybackAlgorithm.UNIDBG
            current = create(PlaybackAlgorithm.UNIDBG)
            revision++
            PlaybackAlgorithm.LOCAL to PlaybackAlgorithm.UNIDBG
        }
        notify(PlaybackAlgorithmChange(
            changed.first,
            changed.second,
            "本地算法连续 $FAILURE_LIMIT 次取址失败",
        ))
        return true
    }

    fun addressSucceeded(attempt: PlaybackAlgorithmAttempt? = null) {
        synchronized(lock) {
            if (attempt != null && attempt.revision != revision) return
            failureCount = 0
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
            probeSuccessCount = 0
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
            probeSuccessCount = 0
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
        /** 本地算法类失败连续这么多次才降级远程；网络失败不算。 */
        const val FAILURE_LIMIT = 3
        /** 本地探测需连续成功这么多次才切回，防半坏算法被偶发成功拉回。 */
        const val PROBE_SUCCESS_LIMIT = 2
    }
}
