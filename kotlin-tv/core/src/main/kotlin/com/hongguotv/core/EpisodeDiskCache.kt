// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 整集落盘缓存池：播放页后台静默下载相邻集，命中后改由本地文件喂数据。
 *
 *  窗口策略：上一集 + 当前集 + 下三集共 5 条，随当前集滑动；
 *  划出窗口的条目立即连同元数据删除（播完即删最早一条）。
 *  地址过期 / 总字节超限 / 磁盘空间不足时整体收缩，播放端永远可以回落在线读取。
 *  磁盘上存的是与在线字节完全一致的密文；解密仍走 [MediaCrypto.decrypt]，
 *  元数据（改写后的明文头 + 加密样本索引 + 已落盘区间）单独存 JSON。 */
class EpisodeDiskCache(
    private val http: OkHttpClient,
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 磁盘可用空间查询（StatFs），core 不直接碰 android.os；读不到时按充足处理。 */
    private val freeBytes: () -> Long = { runCatching { directory.usableSpace }.getOrDefault(Long.MAX_VALUE) },
    private val lowMemory: () -> Boolean = { false },
    /** 地址解析委托给相邻集缓存：取址、重试、模型缓存、即将过期重取全部复用同一套。 */
    private val resolver: (String, Int) -> StreamInfo,
    /** 调度摘要回调（运行日志用）。与按行输出的 [onLog] 分开：摘要会覆盖旧值，不是事件流。 */
    private val onState: (String) -> Unit = {},
    private val onLog: (String) -> Unit = {},
) : Closeable {
    /** 落盘进度回调：每完成一个分段在主线程之外触发，参数为该集当前已落盘比例 0f–1f。 */
    @Volatile var onProgress: (String, Int, Float) -> Unit = { _, _, _ -> }
    /** 最近一次「为什么没下载」的全局原因：空串表示正常调度。供状态页解释三根进度条为何全灰。 */
    @Volatile var skipReason: String = ""
        private set
    /** 当前下载队列长度（含正在下的那条）：0 且 skipReason 为空时说明已全部下完。 */
    @Volatile var queuedCount: Int = 0
        private set

    private fun markSkip(reason: String) {
        if (skipReason != reason) {
            skipReason = reason
            runCatching { onState(reason) }
        }
    }
    private fun setQueued(count: Int) { queuedCount = count }

    /** 元数据视图：播放端只需要这些，样本索引与区间列表不往上传。 */
    data class Meta(val id: String, val quality: Int, val url: String, val key: ByteArray?, val qualityLabel: String,
        val expiresAt: Long, val total: Long, val header: ByteArray, val samples: List<MediaCrypto.Sample>,
        val updatedAt: Long, val done: Boolean) {
        val prepared: MediaCrypto.Prepared get() = MediaCrypto.Prepared(header, samples)
    }
    private class Entry(var meta: Meta, var ranges: List<LongArray>) {
        var doneBytes: Long = ranges.sumOf { it[1] - it[0] }
    }
    private data class Key(val id: String, val quality: Int) {
        val fileName: String get() = "$id-q$quality"
    }
    /** 内部行可升级为完整 Meta；下载线程只取不重解析。 */
    private class Row(val key: Key, val entry: Entry, val file: File, val metaFile: File)

    companion object {
        const val POOL_LIMIT_BYTES = 500L * 1024 * 1024
        const val FREE_FLOOR_BYTES = 1024L * 1024 * 1024
        /** 并发下载线程数：正好对应状态条的三段（下 1/2/3 集）。 */
        const val MAX_PARALLEL = 3
        /** 某一集下载失败后的退避时间：期间不再重提，避免每个 tick 都重试同一集。 */
        private const val RETRY_BACKOFF_MS = 10_000L
        private const val COMPACT_EVERY = 32
        private val ID_PATTERN = Regex("[0-9]{1,30}")
        /** 区间列表序列化：扁平数组 [s0,e0,s1,e1,…]，写入前必须已排序且不重叠。 */
        internal fun rangesToJson(ranges: List<LongArray>) = JSONArray().also { a -> ranges.forEach { a.put(it[0]).put(it[1]) } }
        internal fun rangesFromJson(value: JSONArray?): List<LongArray> {
            if (value == null || value.length() % 2 != 0 || value.length() > 100_000) return emptyList()
            val out = ArrayList<LongArray>(value.length() / 2)
            for (i in 0 until value.length() step 2) {
                val s = value.optLong(i, -1); val e = value.optLong(i + 1, -1)
                if (s < 0 || e <= s) return emptyList()
                out += longArrayOf(s, e)
            }
            return out.sortedBy { it[0] }
        }
        /** 区间并集（输入按起点有序），返回新的有序不相交列表。 */
        internal fun mergeRanges(ranges: List<LongArray>, extra: LongArray? = null): List<LongArray> {
            val all = ArrayList<LongArray>(ranges.size + 1)
            ranges.forEach { all += longArrayOf(it[0], it[1]) }
            if (extra != null && extra[1] > extra[0]) all += longArrayOf(extra[0], extra[1])
            if (all.size <= 1) return all
            all.sortBy { it[0] }
            val out = ArrayList<LongArray>(all.size)
            for (r in all) {
                val last = out.lastOrNull()
                if (last != null && r[0] <= last[1]) { if (r[1] > last[1]) last[1] = r[1] }
                else out += r
            }
            return out
        }
        internal fun covered(ranges: List<LongArray>, offset: Long, length: Long): Boolean {
            if (length <= 0) return true
            val end = offset + length
            var need = offset
            for (r in ranges) {
                if (r[1] <= need) continue
                if (r[0] > need) return false
                need = maxOf(need, r[1])
                if (need >= end) return true
            }
            return false
        }
    }

    private val lock = Object()
    private val entries = LinkedHashMap<Key, Entry>()
    private val threadCount = AtomicInteger()
    /** 三集并发下载：下 1/2/3 集同时开工，不再排成一队等前一集下完。 */
    private val executor = Executors.newFixedThreadPool(MAX_PARALLEL) { action ->
        Thread(action, "episode-disk-${threadCount.incrementAndGet()}").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    /** 目标窗口，按优先级排列（下一集 > +2 > +3 > 当前集 > 上一集）。 */
    private var want = emptyList<Key>()
    private var scheduled = false
    /** 此刻真在下载的集：[want] 是目标窗口，这里是线程池里实际跑着的子集。 */
    private val active = mutableSetOf<Key>()
    /** 失败后的退避截止时间：同一集失败后 10 秒内不再重提，避免每个 tick 都重试。 */
    private val retryAt = mutableMapOf<Key, Long>()
    /** 本轮累计下载字节：超过 [POOL_LIMIT_BYTES] 就整体停手，由下一次播放会话重新计数。 */
    private val downloadedThisRun = AtomicLong()
    /** 预算是否已被本轮耗尽：耗尽后要等 schedule() 里显式复位，避免每个 tick 重启一轮。 */
    @Volatile private var budgetExhausted = false

    init {
        directory.mkdirs()
        sweep()
        // 元数据损坏的条目只废弃该条，不影响其余缓存
        directory.listFiles { f -> f.isFile && f.name.endsWith(".meta") }?.forEach { metaFile ->
            runCatching { loadRow(metaFile) }.onSuccess { row -> row?.let { entries[it.key] = it.entry } }
                .onFailure { metaFile.delete(); File(directory, metaFile.name.removeSuffix(".meta") + ".mp4").delete() }
        }
        entries.values.forEach { entry ->
            if (entry.meta.expiresAt > 0 && entry.meta.expiresAt <= clock()) entry.meta = entry.meta.copy(done = false)
        }
        evictLocked()
    }

    // ---------- 元数据 ----------

    private fun metaFile(key: Key) = File(directory, key.fileName + ".meta")
    private fun dataFile(key: Key) = File(directory, key.fileName + ".mp4")

    /** 便捷位：core 里 base64/hex 助手是 MediaCrypto 的私有实现，这里本地补一份。 */
    private fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    private fun decode64(value: String): ByteArray = java.util.Base64.getDecoder().decode(value)

    private fun loadRow(file: File): Row? {
        val o = JSONObject(file.readText())
        val id = o.getString("id")
        require(ID_PATTERN.matches(id))
        val quality = o.getInt("quality").coerceIn(0, 10_000)
        val key = Key(id, quality)
        val samples = o.optJSONArray("samples")?.let { a ->
            if (a.length() > 200_000) throw IOException("样本索引过多")
            (0 until a.length()).map { i ->
                val s = a.getJSONObject(i)
                MediaCrypto.Sample(s.getLong("s"), s.getLong("e"), decode64(s.getString("iv")), s.optLong("o", 0))
            }
        }.orEmpty()
        val meta = Meta(id, quality, o.getString("url"),
            o.optString("key").takeIf { it.isNotEmpty() }?.let(::decode64),
            o.optString("qualityLabel"), o.optLong("expiresAt"), o.getLong("total"),
            decode64(o.getString("header")), samples, o.optLong("updatedAt"), o.optBoolean("done"))
        require(meta.total > 0)
        val data = dataFile(key)
        val entry = Entry(meta, rangesFromJson(o.optJSONArray("ranges")))
        entry.doneBytes = entry.ranges.sumOf { it[1] - it[0] }
        return if (data.isFile && data.length() > 0 && entry.doneBytes > 0) Row(key, entry, data, file)
        else { data.delete(); null }
    }

    /** tmp+rename 原子落盘，崩溃最多丢未落的那一段进度。 */
    private fun saveMeta(key: Key, entry: Entry) {
        val m = entry.meta
        val samples = JSONArray()
        m.samples.forEach { s -> samples.put(JSONObject().put("s", s.start).put("e", s.end).put("iv", base64(s.iv)).also { if (s.encryptedOffset != 0L) it.put("o", s.encryptedOffset) }) }
        val o = JSONObject().put("id", m.id).put("quality", m.quality).put("url", m.url)
            .put("key", if (m.key != null) base64(m.key) else "").put("qualityLabel", m.qualityLabel)
            .put("expiresAt", m.expiresAt).put("total", m.total).put("header", base64(m.header))
            .put("samples", samples).put("updatedAt", m.updatedAt).put("done", m.done)
            .put("ranges", rangesToJson(entry.ranges))
        val tmp = File(directory, key.fileName + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(metaFile(key))) { metaFile(key).delete(); tmp.renameTo(metaFile(key)) }
    }

    // ---------- 播放端查询 ----------

    @Synchronized fun meta(id: String, quality: Int): Meta? {
        val entry = entries[Key(id, quality)] ?: return null
        if (!entry.meta.done) return null
        if (entry.meta.expiresAt > 0 && entry.meta.expiresAt <= clock() + 60_000L) {
            entry.meta = entry.meta.copy(done = false)
            runCatching { saveMeta(Key(id, quality), entry) }
            return null
        }
        return entry.meta
    }

    /** 判定 [offset, offset+length) 是否完全落盘；失败视为未覆盖，调用方回落在线。 */
    fun covered(id: String, quality: Int, offset: Long, length: Long): Boolean = synchronized(lock) {
        val entry = entries[Key(id, quality)] ?: return false
        runCatching { covered(entry.ranges, offset, length) }.getOrDefault(false)
    }

    /** 已落盘比例 0f–1f：状态栏/进度条用，没有该集条目时返回 0f。 */
    fun progress(id: String, quality: Int): Float = synchronized(lock) {
        val entry = entries[Key(id, quality)] ?: return 0f
        val total = entry.meta.total
        if (total <= 0) 0f else (entry.doneBytes.toFloat() / total).coerceIn(0f, 1f)
    }

    /** 提取器会越过文件末尾多读一点：按总长截断，只答应真正落盘的部分。 */
    fun read(id: String, quality: Int, offset: Long, target: ByteArray, targetOffset: Int, length: Int): Int = synchronized(lock) {
        val entry = entries[Key(id, quality)] ?: return 0
        val safeOffset = offset.coerceIn(0, entry.meta.total)
        val end = (safeOffset + length).coerceAtMost(entry.meta.total)
        if (!covered(entry.ranges, safeOffset, end - safeOffset)) return 0
        runCatching {
            RandomAccessFile(dataFile(Key(id, quality)), "r").use { file ->
                file.seek(safeOffset)
                file.read(target, targetOffset, (end - safeOffset).toInt())
            }
        }.getOrDefault(-1)
    }

    // ---------- 调度 ----------

    /** 下载目标窗口：ordered 按优先级排列（下一集 > +2 > +3 > 当前集 > 上一集）。
     *  保留窗口保留所有已有条目（含部分下载），再按播放窗口裁剪；
     *  返回 false 表示磁盘空间不足，本轮放弃调度。 */
    fun schedule(orderedIds: List<String>, quality: Int, retainedIds: Set<String>, retainedQuality: Int): Boolean {
        if (closed) { markSkip("缓存池已关闭"); return false }
        val keys = orderedIds.filter { ID_PATTERN.matches(it) }.distinct().map { Key(it, quality) }
        synchronized(lock) {
            if (freeBytes() < FREE_FLOOR_BYTES || lowMemory()) {
                markSkip("磁盘空间或内存不足（可用 ${freeBytes() / 1048576}MB）")
                onLog("Episode disk cache paused: storage or memory constrained, free=${freeBytes() / 1048576}MB lowMemory=${lowMemory()}")
                return false
            }
            // 空目标列表是「播放失败/收尾时收缩窗口」的正常调用，不应覆盖真实的跳过原因
            if (keys.isNotEmpty()) markSkip("")
            val before = entries.size
            onLog("Episode disk cache schedule: targets=${keys.joinToString(",") { "${it.id}-q${it.quality}" }}")
            // 保留窗口外才删。正在下载的集如果刚好滑出窗口，不在这里硬删：
            // 并发下载期间频繁删条目会让工作者反复「下到一半被打断 → 重新排队」。
            entries.keys.filter { it.id !in retainedIds || it.quality != retainedQuality }
                .filter { it !in active }.toList().forEach { dropLocked(it) }
            // 总量与集数上限：最旧的非窗口目标先删，窗口内的下载目标永远受保护。
            evictLocked()
            if (entries.size != before) onLog("Episode disk cache evicted, ${entries.size} entries left")
            // 目标窗口变了（切集 / 换画质）才算新的一轮，复位下载预算；同一窗口每秒重复调度
            // 不清零，否则「本轮上限」形同虚设。进入播放页由 MainActivity 调 resetBudget() 显式复位。
            if (keys != want && budgetExhausted) { budgetExhausted = false; downloadedThisRun.set(0L) }
            want = keys
            scheduled = true
            // 切集只换目标窗口：正在下的三条不必打断，空出来的槽位立刻补上新的「下 1/2/3 集」
            ensureWorkerLocked()
        }
        return true
    }

    private fun evictLocked() {
        // 正在下载的条目不能被逐出：download() 里发现 entries 里已没有自己会抛异常退出，
        // 刚下的几 MiB 全部白费，而且下一轮 schedule 又会重新排队——正是「老是卡在排队」的来源之一。
        fun candidates() = entries.keys.filter { it !in active }
        while (entries.size > 5) {
            val pool = candidates()
            if (pool.isEmpty()) break
            val victim = pool.filter { it !in want }.minByOrNull { entries[it]!!.meta.updatedAt }
                ?: pool.minByOrNull { entries[it]!!.meta.updatedAt } ?: break
            dropLocked(victim)
        }
        while (entries.values.sumOf { it.doneBytes } > POOL_LIMIT_BYTES) {
            val pool = candidates()
            if (pool.isEmpty()) break
            val victim = pool.filter { it !in want }.minByOrNull { entries[it]!!.meta.updatedAt }
                ?: pool.minByOrNull { entries[it]!!.meta.updatedAt } ?: break
            dropLocked(victim)
        }
    }

    private fun dropLocked(key: Key) {
        entries.remove(key)
        dataFile(key).delete()
        metaFile(key).delete()
    }

    fun clear() {
        synchronized(lock) {
            want = emptyList(); scheduled = false; active.clear(); retryAt.clear()
            setQueued(0); markSkip("缓存已清空")
            entries.keys.toList().forEach { dropLocked(it) }
        }
        // 线程池不 shutdown：clear() 只是清队列，开关重新打开后还要继续用。
        // 正在跑的 download 会在下一段开头发现条目已被移除而自行退出。
    }

    /** 新一轮播放会话开始（进入/切换播放页）：复位本轮下载预算，并允许重新调度。 */
    fun resetBudget() {
        budgetExhausted = false
        downloadedThisRun.set(0L)
        synchronized(lock) {
            if (!closed && want.isNotEmpty()) { scheduled = true; ensureWorkerLocked() }
        }
    }

    /** 地址失效级联：废弃该集全部画质的落盘条目，下载线程下一轮会跳过。 */
    fun invalidate(id: String) {
        synchronized(lock) {
            entries.keys.filter { it.id == id }.toList().forEach { dropLocked(it) }
            retryAt.keys.removeAll { it.id == id }
        }
        runCatching { onProgress(id, -1, 0f) }
    }

    override fun close() {
        closed = true
        synchronized(lock) { active.clear() }
        executor.shutdownNow()
    }

    /**
     * 每次 schedule 后补齐下载线程：窗口里还没下完、且没在下载的集，
     * 逐个提交到线程池。已经跑着的集不会重复提交，切集只换目标窗口、不打断正在下的段。
     */
    private fun ensureWorkerLocked() {
        if (closed || !scheduled) return
        val now = clock()
        val pending = want.filter { key ->
            key !in active && (retryAt[key] ?: 0L) <= now && needsDownloadLocked(key)
        }
        // 未开始下载的集按优先级补到并发上限（下 1/2/3 集同时开工）
        pending.take(MAX_PARALLEL - active.size).forEach { key ->
            active += key
            executor.execute { downloadWorker(key) }
        }
        // 队列长度 = 还没下完的集数（含正在下的），供日志/摘要显示
        setQueued(want.count { needsDownloadLocked(it) })
        // 目标窗口全部下完时把摘要恢复成空串（= 正常）。这里曾经写「队列已清空，等待下一次
        // 播放调度」，界面会照着显示成还在排队，和「已全部缓存」正好相反。
        if (queueIsEmptyLocked() && skipReason.isNotEmpty() && retryAt.isEmpty()) markSkip("")
    }

    /** 这一集是否还需要下载：没有条目、没标记完成，或者有区间缺口。 */
    private fun needsDownloadLocked(key: Key): Boolean {
        val entry = entries[key] ?: return true
        return !entry.meta.done || !covered(entry.ranges, 0, entry.meta.total)
    }

    private fun queueIsEmptyLocked(): Boolean = want.none { needsDownloadLocked(it) }

    // ---------- 下载线程（并发，每个线程一条集） ----------

    /** 单个下载工作者：一次只负责一集，下完就退出，由下一次 schedule 决定要不要继续。 */
    private fun downloadWorker(key: Key) {
        try {
            // 地址解析与索引 prepare 要走网络：在锁外做，否则几秒内所有读取/调度都被堵死
            val existing = synchronized(lock) { entries[key]?.let { Row(key, it, dataFile(key), metaFile(key)) } }
            // 切集后这一集可能已经划出窗口：不再白下一遍
            if (existing == null && synchronized(lock) { key !in want || closed }) return
            val row = existing ?: openRow(key)
            if (row == null) {
                synchronized(lock) {
                    retryAt[key] = clock() + RETRY_BACKOFF_MS
                    markSkip(skipReason.ifBlank { "取址失败，稍后重试" })
                }
                return
            }
            download(row)
            synchronized(lock) {
                if (entries[row.key] === row.entry) {
                    row.entry.meta = row.entry.meta.copy(done = true, updatedAt = clock())
                    row.entry.doneBytes = row.entry.meta.total
                    runCatching { saveMeta(row.key, row.entry) }
                    if (skipReason.isNotEmpty()) markSkip("")
                    onLog("Episode cached ${row.key.id} q${row.key.quality} ${row.entry.meta.total / 1048576}MB")
                }
            }
            runCatching { onProgress(row.key.id, row.key.quality, 1f) }
        } catch (cancelled: InterruptedException) {
            // 线程池关闭或 cache.clear()，直接退出
        } catch (problem: Throwable) {
            if (closed) return
            // 地址过期：整条作废，等下一轮 schedule 用新地址重新整集下载
            val stale = synchronized(lock) {
                val entry = entries[key]
                if (entry == null) { retryAt[key] = clock() + RETRY_BACKOFF_MS; false }
                else if (entry.meta.expiresAt > 0 && entry.meta.expiresAt <= clock()) {
                    markSkip("播放地址已过期，等下一轮重新取址")
                    dropLocked(key); true
                } else { retryAt[key] = clock() + RETRY_BACKOFF_MS; false }
            }
            if (!stale) {
                // 失败原因写进摘要：只记在日志里，用户点开时往往已经被下一轮的成功/失败刷掉
                val reason = "下载失败：${problem.javaClass.simpleName} ${problem.message.orEmpty().take(120)}"
                markSkip(reason)
                onLog("Episode cache download failed $key: $reason")
            }
        } finally {
            synchronized(lock) {
                active -= key
                setQueued(want.count { needsDownloadLocked(it) })
                // 这一集收工后立刻补位：窗口里还有没下的集就继续开工
                ensureWorkerLocked()
            }
        }
    }

    /** 缺元数据则解析地址并整段 prepare（约两三跳网络），之后按 4MiB 段续传。锁外执行。 */
    private fun openRow(key: Key): Row? {
        synchronized(lock) { entries[key]?.let { return Row(key, it, dataFile(key), metaFile(key)) } }
        val info = runCatching { resolver(key.id, key.quality) }.getOrElse {
            // 整部剧都下不来时最常见的原因是取址失败：这条必须留下，否则界面只能看到一片灰
            val reason = "取址失败：${it.javaClass.simpleName} ${it.message.orEmpty().take(120)}"
            markSkip(reason)
            onLog("Episode cache resolve failed ${key.id} q${key.quality}: $reason")
            return null
        }
        if (info.expiresAt > 0 && info.expiresAt <= clock() + 60_000L) {
            markSkip("播放地址将在 60 秒内过期，暂不下整集")
            onLog("Episode cache skipped ${key.id} q${key.quality}: stream expires within 60s")
            return null
        }
        val remote = RemoteVideo(http, info, chunkBytes = 256 * 1024)
        try {
            remote.prepare()
            val snapshot = remote.preparedSnapshot()
            val meta = Meta(key.id, key.quality, info.url, info.key, info.quality, info.expiresAt,
                remote.total, snapshot.header, snapshot.samples, clock(), false)
            val entry = Entry(meta, emptyList())
            synchronized(lock) {
                if (closed || entries.containsKey(key) || key !in want && entries.size >= 5) return null
                entries[key] = entry
                saveMeta(key, entry)
            }
            return Row(key, entry, dataFile(key), metaFile(key))
        } catch (problem: Throwable) {
            // prepare 失败同样静默：补一条带原因的日志与状态摘要
            val reason = "解析视频信息失败：${problem.javaClass.simpleName} ${problem.message.orEmpty().take(120)}"
            markSkip(reason)
            onLog("Episode cache prepare failed ${key.id} q${key.quality}: $reason")
            return null
        } finally {
            remote.close()
        }
    }

    private fun download(row: Row) {
        val total = row.entry.meta.total
        // 4MiB 一段：一次请求失败只重传这一段，进度按段落盘
        val segment = 4L * 1024 * 1024
        var pendingMeta = 0
        RandomAccessFile(row.file, "rw").use { file ->
            var at = 0L
            while (at < total) {
                if (Thread.currentThread().isInterrupted || closed) throw InterruptedException("下载已取消")
                synchronized(lock) {
                    if (entries[row.key] !== row.entry) throw InterruptedException("下载已取消")
                    // 这一集已经滑出下载窗口（用户切到别的集了）：立刻让出线程槽位给新的下 1/2/3 集。
                    // 否则连切几集后三个线程还在下旧集，新窗口只能在后面等——就是「老是卡在排队」。
                    // 已落盘的部分保留在磁盘上（meta 里有区间），以后排到时从缺口续传。
                    if (row.key !in want) throw InterruptedException("已移出下载窗口")
                }
                val end = minOf(total, at + segment)
                synchronized(lock) {
                    if (covered(row.entry.ranges, at, end - at)) { at = end; return@synchronized }
                    row.entry.ranges = mergeRanges(row.entry.ranges, longArrayOf(at, at))
                }
                val rangeStart: Long
                val rangeEnd: Long
                synchronized(lock) {
                    // 段内已落盘的部分跳过：从第一个缺口下起，到段末或下一缺口前为止
                    var s = at
                    for (r in row.entry.ranges) { if (r[1] > s && r[0] <= s) s = r[1] }
                    var e = end
                    for (r in row.entry.ranges) { if (r[0] in (s + 1) until e) { e = r[0]; break } }
                    rangeStart = s; rangeEnd = e
                }
                if (rangeStart >= rangeEnd) { at = end; continue }
                val bytes = fetch(row.entry.meta.url, rangeStart, rangeEnd - 1)
                file.seek(rangeStart)
                file.write(bytes)
                file.fd.sync()
                val ratio = synchronized(lock) {
                    row.entry.ranges = mergeRanges(row.entry.ranges, longArrayOf(rangeStart, rangeEnd))
                    row.entry.doneBytes = row.entry.ranges.sumOf { it[1] - it[0] }
                    // 比例在锁内算好，避免再去 progress() 抢一次锁（三条线程并发下会明显增加争用）
                    if (total <= 0) 0f else (row.entry.doneBytes.toFloat() / total).coerceIn(0f, 1f)
                }
                runCatching { onProgress(row.key.id, row.key.quality, ratio) }
                // 并发下载下没有「本轮循环」可退出了：超出预算就整体停手，
                // 由下一次播放会话（重新进播放页 / 切集时 schedule 复位）重新开始。
                if (downloadedThisRun.addAndGet(bytes.size.toLong()) > POOL_LIMIT_BYTES && !budgetExhausted) {
                    budgetExhausted = true
                    markSkip("本轮下载量已达上限，暂停到下次播放")
                    onLog("Episode disk cache paused: run budget exhausted")
                    // 只停调度，不清 want：want 一变就会被当成「新一轮」而复位预算
                    synchronized(lock) { scheduled = false; setQueued(0) }
                    throw InterruptedException("本轮下载量已达上限")
                }
                // 其他线程看到预算耗尽后也随下一段让出
                if (budgetExhausted) throw InterruptedException("本轮下载量已达上限")
                if (++pendingMeta >= COMPACT_EVERY) { pendingMeta = 0; runCatching { saveMeta(row.key, row.entry) } }
                at = end
            }
        }
        if (pendingMeta > 0) runCatching { saveMeta(row.key, row.entry) }
    }

    private fun fetch(url: String, start: Long, end: Long): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", VendorConstants.VIDEO_UA)
            .header("Referer", "https://novel.snssdk.com/").header("Range", "bytes=$start-$end")
            .header("Accept-Encoding", "identity").build()
        val call = http.newCall(request)
        try {
            return RequestScope.execute(call) { response ->
                if (response.code != 206) throw IOException("视频服务器不支持分段读取（HTTP ${response.code}）")
                val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                    ?: throw IOException("视频分段响应异常")
                if (match.destructured.component1().toLong() != start || match.destructured.component2().toLong() != end)
                    throw IOException("视频分段范围不匹配")
                response.body?.bytes() ?: throw IOException("视频分段为空")
            }
        } finally {
            call.cancel()
        }
    }

    // ---------- 启动清扫 ----------

    private fun sweep() {
        directory.listFiles()?.forEach { file ->
            val name = file.name
            when {
                name.endsWith(".tmp") -> file.delete()
                name.endsWith(".meta") -> Unit
                name.endsWith(".mp4") -> {
                    val base = name.removeSuffix(".mp4")
                    if (!File(directory, "$base.meta").isFile) file.delete()
                }
            }
        }
    }
}
