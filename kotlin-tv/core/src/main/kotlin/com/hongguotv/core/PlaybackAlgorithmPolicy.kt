// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** 当前播放算法，与设置页的三种算法模式一一对应。 */
enum class PlaybackAlgorithm {
    LOCAL,
    UNIDBG,
    FRIDA,
}

/** 用户手动切换算法的通知；界面据此同步设置。 */
data class PlaybackAlgorithmChange(
    val from: PlaybackAlgorithm?,
    val to: PlaybackAlgorithm,
    val reason: String,
)

/** 一次取址开始时绑定的算法与播放版本；切换前的旧请求不能影响新选择或新剧集。 */
data class PlaybackAlgorithmAttempt internal constructor(
    internal val revision: Long,
)

/** 当前模式连续三次取址失败；每轮失败独立通知，界面可据此避免重复弹窗。 */
class PlaybackAlgorithmUnavailable internal constructor(val algorithm: PlaybackAlgorithm)

/** 播放算法只由用户选择；连续取址失败时通知界面，不自动更换算法或签名器。 */
class PlaybackAlgorithmPolicy(
    initial: PlaybackAlgorithm?,
    private val localFactory: () -> RemoteSigner = { RemoteSigner() },
    private val unidbgFactory: () -> RemoteSigner = { UnidbgSigner() },
    private val fridaFactory: () -> RemoteSigner = { FridaSigner() },
    onChange: ((PlaybackAlgorithmChange) -> Unit)? = null,
    onUnavailable: ((PlaybackAlgorithmUnavailable) -> Unit)? = null,
) {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(PlaybackAlgorithmChange) -> Unit>().apply {
        onChange?.let(::add)
    }
    private val unavailableListeners = java.util.concurrent.CopyOnWriteArrayList<(PlaybackAlgorithmUnavailable) -> Unit>().apply {
        onUnavailable?.let(::add)
    }

    private val lock = Any()
    private var selected = initial
    private var current = create(initial)
    private var failureCount = 0
    private var revision = 0L
    private var playbackSeriesId: String? = null
    private var currentUnavailable: PlaybackAlgorithmUnavailable? = null

    /** 当前实际用于播放取址的签名器。 */
    val signer: RemoteSigner get() = synchronized(lock) { current }
    val algorithm: PlaybackAlgorithm? get() = synchronized(lock) { selected }
    /** 保留尚未恢复的失败状态，应用回到前台时仍可提示用户。 */
    val unavailable: PlaybackAlgorithmUnavailable? get() = synchronized(lock) { currentUnavailable }

    /** 换剧或确认重播后重新累计失败，旧剧集的在途请求不能压住新一轮提示。 */
    fun beginPlayback(seriesId: String, restart: Boolean = false) {
        synchronized(lock) {
            if (playbackSeriesId == seriesId && !restart) return
            playbackSeriesId = seriesId
            failureCount = 0
            currentUnavailable = null
            revision++
        }
    }

    /** 在真正发起取址前记录当前选择版本。 */
    fun beginAddress(): PlaybackAlgorithmAttempt = synchronized(lock) {
        PlaybackAlgorithmAttempt(revision)
    }

    /** 记录一次完整取址失败；每段连续失败只在第三次通知，不自动切换算法。 */
    fun addressFailed(attempt: PlaybackAlgorithmAttempt? = null): Boolean {
        val failure = synchronized(lock) {
            if (attempt != null && attempt.revision != revision) return false
            val algorithm = selected ?: return false
            if (currentUnavailable != null) return false
            failureCount++
            if (failureCount < FAILURE_LIMIT) return false
            PlaybackAlgorithmUnavailable(algorithm).also { currentUnavailable = it }
        }
        unavailableListeners.forEach { runCatching { it(failure) } }
        return true
    }

    /** 成功获取播放地址后，重新累计下一段连续失败。 */
    fun addressSucceeded(attempt: PlaybackAlgorithmAttempt? = null) {
        synchronized(lock) {
            if (attempt != null && attempt.revision != revision) return
            failureCount = 0
            currentUnavailable = null
        }
    }

    /** 设置页显式切换；相同算法不重复创建签名器。 */
    fun select(next: PlaybackAlgorithm) {
        val from = synchronized(lock) {
            if (selected == next) return
            val old = selected
            val signer = create(next)
            selected = next
            current = signer
            failureCount = 0
            currentUnavailable = null
            revision++
            old
        }
        val change = PlaybackAlgorithmChange(from, next, "用户选择")
        listeners.forEach { runCatching { it(change) } }
    }

    fun addListener(listener: (PlaybackAlgorithmChange) -> Unit) { if (listener !in listeners) listeners += listener }
    fun removeListener(listener: (PlaybackAlgorithmChange) -> Unit) { listeners -= listener }
    fun addUnavailableListener(listener: (PlaybackAlgorithmUnavailable) -> Unit) { if (listener !in unavailableListeners) unavailableListeners += listener }
    fun removeUnavailableListener(listener: (PlaybackAlgorithmUnavailable) -> Unit) { unavailableListeners -= listener }

    private fun create(algorithm: PlaybackAlgorithm?): RemoteSigner = when (algorithm) {
        PlaybackAlgorithm.LOCAL -> localFactory()
        PlaybackAlgorithm.UNIDBG -> unidbgFactory()
        PlaybackAlgorithm.FRIDA -> fridaFactory()
        null -> RemoteSigner()
    }

    private companion object {
        const val FAILURE_LIMIT = 3
    }
}
