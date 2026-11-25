// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** 自定义算法由设置层选择具体配置，不加入内置算法的自动降级链。 */
enum class PlaybackAlgorithm { LOCAL, UNIDBG, FRIDA, CUSTOM }

/** 算法变化只影响后续取址，不要求播放器重建正在使用的媒体源。 */
data class PlaybackAlgorithmChange(
    val from: PlaybackAlgorithm?,
    val to: PlaybackAlgorithm,
    val reason: String,
    val automatic: Boolean = false,
    internal val revision: Long = 0,
)

/** 一次取址固定使用同一个签名器，版本与成功水位用于丢弃过期的并发结果。 */
data class PlaybackAlgorithmAttempt internal constructor(
    internal val revision: Long,
    internal val sequence: Long,
    val algorithm: PlaybackAlgorithm?,
    val signer: RemoteSigner,
)

/** 仅手动模式的前台连续失败通知；自动模式使用有界降级和冷却。 */
class PlaybackAlgorithmUnavailable internal constructor(val algorithm: PlaybackAlgorithm)

/** 本地优先，依次降级至 unidbg、Frida；恢复探测全局单飞，网络操作始终在锁外执行。 */
class PlaybackAlgorithmPolicy(
    initial: PlaybackAlgorithm?,
    private val localFactory: () -> RemoteSigner = { RemoteSigner() },
    private val unidbgFactory: () -> RemoteSigner = { UnidbgSigner() },
    private val fridaFactory: () -> RemoteSigner = { FridaSigner() },
    private val customFactory: () -> RemoteSigner = { UnidbgSigner() },
    onChange: ((PlaybackAlgorithmChange) -> Unit)? = null,
    onUnavailable: ((PlaybackAlgorithmUnavailable) -> Unit)? = null,
    automatic: Boolean = false,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val recoveryPause: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val listeners = CopyOnWriteArrayList<(PlaybackAlgorithmChange) -> Unit>().apply { onChange?.let(::add) }
    private val unavailableListeners = CopyOnWriteArrayList<(PlaybackAlgorithmUnavailable) -> Unit>().apply { onUnavailable?.let(::add) }
    private val lock = Any()
    private var auto = automatic && initial != null
    private var selected = if (auto) PlaybackAlgorithm.LOCAL else initial
    private val signers = mutableMapOf<PlaybackAlgorithm?, RemoteSigner>()
    private var current = create(selected)
    private var failureCount = 0
    private var revision = 0L
    private var preferenceRevision = 0L
    private var sequence = 0L
    private var successWatermark = 0L
    private var playbackSeriesId: String? = null
    private var currentUnavailable: PlaybackAlgorithmUnavailable? = null
    private data class Health(var failures: Int = 0, var retryAt: Long = 0)
    private val health = mutableMapOf<PlaybackAlgorithm, Health>()
    private var recoveryAttempt: PlaybackAlgorithmAttempt? = null
    private var lastRecoveryAt: Long? = null
    private val recoveryWorker by lazy {
        Executors.newSingleThreadExecutor { action -> Thread(action, "playback-algorithm-recovery").apply { isDaemon = true } }
    }

    val signer: RemoteSigner get() = synchronized(lock) { current }
    val algorithm: PlaybackAlgorithm? get() = synchronized(lock) { selected }
    val isAutomatic: Boolean get() = synchronized(lock) { auto }
    val unavailable: PlaybackAlgorithmUnavailable? get() = synchronized(lock) { currentUnavailable }

    /** 换剧只重置前台提示，不清除服务冷却，也不允许旧剧结果修改新剧的策略。 */
    fun beginPlayback(seriesId: String, restart: Boolean = false) {
        synchronized(lock) {
            if (playbackSeriesId == seriesId && !restart) return
            playbackSeriesId = seriesId
            failureCount = 0
            currentUnavailable = null
            revision++
        }
    }

    fun beginAddress(): PlaybackAlgorithmAttempt = synchronized(lock) { attempt() }

    /** 本地可重试至三次阈值，远程每轮只尝试一次；全部冷却时由播放页原有重试接管。 */
    internal fun nextAddress(tried: Set<PlaybackAlgorithm?>): PlaybackAlgorithmAttempt? {
        var change: PlaybackAlgorithmChange? = null
        val result = synchronized(lock) {
            if (!auto) return@synchronized if (selected in tried) null else attempt()
            val next = selected?.takeIf { it !in tried && available(it) && recoveryAttempt?.algorithm != it }
                ?: ORDER.firstOrNull { it !in tried && available(it) && recoveryAttempt?.algorithm != it }
                ?: return@synchronized null
            if (next != selected) change = switchTo(next, "冷却结束后重试")
            attempt()
        }
        notifyChange(change)
        return result
    }

    /** 缓存命中不调用这里：旧地址可播放并不能证明当前算法已经恢复。 */
    fun addressSucceeded(attempt: PlaybackAlgorithmAttempt? = null) {
        synchronized(lock) {
            if (attempt != null && !matches(attempt)) return
            successWatermark = maxOf(successWatermark, attempt?.sequence ?: sequence)
            failureCount = 0
            currentUnavailable = null
            selected?.let { health.remove(it) }
        }
    }

    /** 只接收正式播放的完整失败；预取、下载、取消不允许触发全局降级和弹窗。 */
    fun addressFailed(attempt: PlaybackAlgorithmAttempt? = null): Boolean {
        var change: PlaybackAlgorithmChange? = null
        var unavailable: PlaybackAlgorithmUnavailable? = null
        synchronized(lock) {
            if (attempt != null && (!matches(attempt) || attempt.sequence <= successWatermark)) return false
            val algorithm = selected ?: return false
            if (auto) {
                // 本地累计三次完整失败才降级；成功会清零，旧成功之前发出的失败不再累计。
                if (algorithm == PlaybackAlgorithm.LOCAL && ++failureCount < FAILURE_LIMIT) return false
                // 同一版本的并发失败只打开一次熔断，随后到达的旧请求失效。
                coolDown(algorithm)
                val next = ORDER.dropWhile { it != algorithm }.drop(1).firstOrNull { available(it) }
                if (next != null) change = switchTo(next, "当前算法取址失败，自动降级")
                else revision++
            } else {
                if (currentUnavailable != null || ++failureCount < FAILURE_LIMIT) return false
                unavailable = PlaybackAlgorithmUnavailable(algorithm).also { currentUnavailable = it }
            }
        }
        notifyChange(change)
        unavailable?.let { failure -> unavailableListeners.forEach { runCatching { it(failure) } } }
        return change != null || unavailable != null
    }

    /** 本地探测一轮最多三次，分别等待二秒和四秒；整轮失败后冷却五分钟，全局单飞。 */
    internal fun recover(probe: (PlaybackAlgorithmAttempt) -> Boolean): java.util.concurrent.Future<*>? {
        var preference = 0L
        val attempt = synchronized(lock) {
            if (!auto || selected == PlaybackAlgorithm.LOCAL || recoveryAttempt != null) return null
            val now = clock()
            if (!available(PlaybackAlgorithm.LOCAL)) return null
            if (lastRecoveryAt?.let { now - it < LOCAL_RECOVERY_COOLDOWN_MS } == true) return null
            preference = preferenceRevision
            lastRecoveryAt = now
            // 冷却到期后独立探测，不等待远程当前请求结束。
            PlaybackAlgorithmAttempt(revision, ++sequence, PlaybackAlgorithm.LOCAL, create(PlaybackAlgorithm.LOCAL))
                .also { recoveryAttempt = it }
        }
        return recoveryWorker.submit {
            var succeeded = false
            try {
                for (round in 0..RECOVERY_RETRY_DELAYS_MS.size) {
                    if (!recoveryCurrent(preference)) break
                    if (round > 0) recoveryPause(RECOVERY_RETRY_DELAYS_MS[round - 1])
                    // 等待期间关闭自动切换或手动改算法后，不能再发出下一次探测。
                    if (!recoveryCurrent(preference)) break
                    succeeded = try { probe(attempt) } catch (_: java.io.IOException) { false }
                    if (succeeded) break
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                var change: PlaybackAlgorithmChange? = null
                synchronized(lock) {
                    if (recoveryAttempt === attempt) recoveryAttempt = null
                    // 自动远程降级不使本地成功失效；只有用户改动开关或算法才撤销探测结果。
                    if (auto && preferenceRevision == preference) {
                        val algorithm = requireNotNull(attempt.algorithm)
                        if (succeeded) {
                            health.remove(algorithm)
                            change = switchTo(algorithm, "真实取址探测成功，恢复优先算法")
                        } else coolDown(algorithm)
                    }
                }
                notifyChange(change)
            }
        }
    }

    fun selectAutomatic() {
        val change = synchronized(lock) {
            if (auto) return
            create(PlaybackAlgorithm.LOCAL)
            preferenceRevision++
            auto = true
            health.clear()
            lastRecoveryAt = null
            switchTo(PlaybackAlgorithm.LOCAL, "用户启用自动切换")
        }
        notifyChange(change)
    }

    fun select(next: PlaybackAlgorithm) = select(next, null)

    /** 自定义配置即使枚举相同，也可通过显式工厂更换签名器。 */
    fun select(next: PlaybackAlgorithm, factory: (() -> RemoteSigner)?) {
        val change = synchronized(lock) {
            if (!auto && selected == next && factory == null) return
            // 先构造，失败时保留当前模式和状态。
            val signer = factory?.invoke() ?: create(next)
            signers[next] = signer
            preferenceRevision++
            auto = false
            health.clear()
            lastRecoveryAt = null
            switchTo(next, "用户选择")
        }
        notifyChange(change)
    }

    /** 主线程排队的旧通知不能覆盖后到达的切换。 */
    fun isCurrent(change: PlaybackAlgorithmChange): Boolean = synchronized(lock) { change.revision == revision }
    fun addListener(listener: (PlaybackAlgorithmChange) -> Unit) { if (listener !in listeners) listeners += listener }
    fun removeListener(listener: (PlaybackAlgorithmChange) -> Unit) { listeners -= listener }
    fun addUnavailableListener(listener: (PlaybackAlgorithmUnavailable) -> Unit) { if (listener !in unavailableListeners) unavailableListeners += listener }
    fun removeUnavailableListener(listener: (PlaybackAlgorithmUnavailable) -> Unit) { unavailableListeners -= listener }

    private fun recoveryCurrent(preference: Long): Boolean = synchronized(lock) {
        auto && preferenceRevision == preference && !Thread.currentThread().isInterrupted
    }
    private fun matches(attempt: PlaybackAlgorithmAttempt) = attempt.revision == revision && attempt.signer === current
    private fun attempt() = PlaybackAlgorithmAttempt(revision, ++sequence, selected, current)
    private fun available(algorithm: PlaybackAlgorithm) = health[algorithm]?.let { clock() >= it.retryAt } ?: true
    private fun coolDown(algorithm: PlaybackAlgorithm) {
        val state = health.getOrPut(algorithm) { Health() }
        state.failures = (state.failures + 1).coerceAtMost(5)
        state.retryAt = clock() + if (algorithm == PlaybackAlgorithm.LOCAL) LOCAL_RECOVERY_COOLDOWN_MS
            else minOf(MAX_COOLDOWN_MS, INITIAL_COOLDOWN_MS * (1L shl (state.failures - 1)))
    }
    private fun switchTo(next: PlaybackAlgorithm, reason: String): PlaybackAlgorithmChange {
        val signer = create(next)
        val previous = selected
        selected = next
        current = signer
        failureCount = 0
        currentUnavailable = null
        revision++
        return PlaybackAlgorithmChange(previous, next, reason, auto, revision)
    }
    private fun notifyChange(change: PlaybackAlgorithmChange?) {
        if (change != null) listeners.forEach { runCatching { it(change) } }
    }
    private fun create(algorithm: PlaybackAlgorithm?): RemoteSigner = signers.getOrPut(algorithm) {
        when (algorithm) {
            PlaybackAlgorithm.LOCAL -> localFactory()
            PlaybackAlgorithm.UNIDBG -> unidbgFactory()
            PlaybackAlgorithm.FRIDA -> fridaFactory()
            PlaybackAlgorithm.CUSTOM -> customFactory()
            null -> RemoteSigner()
        }
    }
    private companion object {
        val ORDER = listOf(PlaybackAlgorithm.LOCAL, PlaybackAlgorithm.UNIDBG, PlaybackAlgorithm.FRIDA)
        const val FAILURE_LIMIT = 3
        val RECOVERY_RETRY_DELAYS_MS = longArrayOf(2_000L, 4_000L)
        const val LOCAL_RECOVERY_COOLDOWN_MS = 300_000L
        const val INITIAL_COOLDOWN_MS = 30_000L
        const val MAX_COOLDOWN_MS = 300_000L
    }
}
