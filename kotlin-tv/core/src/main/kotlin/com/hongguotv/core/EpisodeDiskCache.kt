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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 整集落盘缓存池：播放页后台静默下载相邻集，命中后改由本地文件喂数据。
 *
 *  只主动下载后三集；离开下载窗口、切剧或切画质时保留已有文件。
 *  播放到某集的下下集后才清理该集；池满时按缓存时间淘汰最旧的非播放条目。
 *  完整缓存不依赖在线地址有效期；池容量按整集大小预留，避免并发下载突破上限。
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
        val updatedAt: Long, val done: Boolean, val seriesId: String = "") {
        val prepared = MediaCrypto.Prepared(header, samples)
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
        // Activity 重建时，新缓存池在后台等待旧池收尾，避免旧快照覆盖新条目。
        private val retiredFiles = ConcurrentHashMap<String, Pair<ExecutorService, ExecutorService>>()
        const val SERIES_LIMIT_EPISODES = 5
        const val POOL_LIMIT_BYTES = 500L * 1024 * 1024
        const val FREE_FLOOR_BYTES = 1024L * 1024 * 1024
        /** 缓冲充足时的并发上限，对应下 1/2/3 集。 */
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
    // 文件操作只使用独立的文件锁；界面查询和调度永远不等待磁盘。
    private val fileLocks = ConcurrentHashMap<Key, Any>()
    private val fileIo = Executors.newSingleThreadExecutor { action ->
        Thread(action, "episode-disk-files").apply { isDaemon = true }
    }
    private data class Resources(val free: Long, val lowMemory: Boolean)
    @Volatile private var resources = Resources(Long.MAX_VALUE, false)
    private var nextResourceCheckAt = 0L
    private val threadCount = AtomicInteger()
    /** 三集并发下载：下 1/2/3 集同时开工，不再排成一队等前一集下完。 */
    private val executor = Executors.newFixedThreadPool(MAX_PARALLEL) { action ->
        Thread(action, "episode-disk-${threadCount.incrementAndGet()}").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    /** 实际下载目标，按调用方提供的优先级排列。 */
    private var want = emptyList<Key>()
    private var downloadSeriesId = ""
    /** 容量淘汰过的下载目标在本窗口内不重下，避免池满时循环淘汰、反复消耗流量。 */
    private val capacityEvicted = mutableSetOf<Key>()
    private var scheduled = false
    /** 此刻真在下载的集：[want] 是目标窗口，这里是线程池里实际跑着的子集。 */
    private val active = mutableSetOf<Key>()
    private val activeScopes = mutableMapOf<Key, RequestScope>()
    /** 起播/缓冲时为 0，缓冲充足后从下一集单路下载逐步恢复到三路。 */
    private var downloadParallelism = MAX_PARALLEL
    /** 失败后的退避截止时间：同一集失败后 10 秒内不再重提，避免每个 tick 都重试。 */
    private val retryAt = mutableMapOf<Key, Long>()
    /** 当前播放文件不参与容量淘汰；已被播放进度淘汰的在途下载先取消、退出后再删。 */
    private var playingId: String? = null
    private val discardAfterDownload = mutableSetOf<Key>()

    init {
        retiredFiles.remove(directory.absolutePath)?.let { (downloads, files) ->
            if (!downloads.awaitTermination(30, TimeUnit.SECONDS) || !files.awaitTermination(30, TimeUnit.SECONDS)) {
                retiredFiles[directory.absolutePath] = downloads to files
                throw IOException("上一缓存池仍在收尾，请稍后重试")
            }
        }
        resources = Resources(freeBytes(), lowMemory())
        directory.mkdirs()
        sweep()
        // 元数据损坏的条目只废弃该条，不影响其余缓存
        directory.listFiles { f -> f.isFile && f.name.endsWith(".meta") }?.forEach { metaFile ->
            runCatching { loadRow(metaFile) }.onSuccess { row ->
                if (row != null) entries[row.key] = row.entry
                else { metaFile.delete(); File(directory, metaFile.name.removeSuffix(".meta") + ".mp4").delete() }
            }
                .onFailure { metaFile.delete(); File(directory, metaFile.name.removeSuffix(".meta") + ".mp4").delete() }
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
            decode64(o.getString("header")), samples, o.optLong("updatedAt"), o.optBoolean("done"), o.optString("seriesId"))
        require(meta.total > 0)
        val data = dataFile(key)
        val entry = Entry(meta, rangesFromJson(o.optJSONArray("ranges")))
        entry.doneBytes = entry.ranges.sumOf { it[1] - it[0] }
        val fileLength = data.length()
        if (entry.ranges.any { it[1] > fileLength }) return null
        return if (data.isFile && fileLength > 0 && entry.doneBytes > 0) Row(key, entry, data, file)
        else { data.delete(); null }
    }

    /** 持锁时只捕获不可变快照；序列化、写入和删除由同一个后台队列保持顺序。 */
    private fun saveMeta(key: Key, entry: Entry): Future<*>? = synchronized(lock) {
        if (entries[key] !== entry) return@synchronized null
        val meta = entry.meta
        val ranges = entry.ranges.map { it.copyOf() }
        // 快照与入队在同一短锁内，旧快照不会排到删除或新条目的快照之后。
        fileTask(key, lockFile = false) { writeMeta(key, meta, ranges) }
    }

    private fun fileTask(key: Key, lockFile: Boolean = true, action: () -> Unit): Future<*>? = try {
        fileIo.submit {
            if (lockFile) synchronized(fileLocks.getOrPut(key) { Any() }) { action() } else action()
        }
    } catch (_: RejectedExecutionException) { null }

    private fun writeMeta(key: Key, m: Meta, ranges: List<LongArray>) {
        val samples = JSONArray()
        m.samples.forEach { s -> samples.put(JSONObject().put("s", s.start).put("e", s.end).put("iv", base64(s.iv)).also { if (s.encryptedOffset != 0L) it.put("o", s.encryptedOffset) }) }
        val o = JSONObject().put("id", m.id).put("quality", m.quality).put("url", m.url)
            .put("key", if (m.key != null) base64(m.key) else "").put("qualityLabel", m.qualityLabel)
            .put("expiresAt", m.expiresAt).put("total", m.total).put("header", base64(m.header))
            .put("samples", samples).put("updatedAt", m.updatedAt).put("done", m.done).put("seriesId", m.seriesId)
            .put("ranges", rangesToJson(ranges))
        val tmp = File(directory, key.fileName + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(metaFile(key))) { metaFile(key).delete(); tmp.renameTo(metaFile(key)) }
    }

    /** 资源查询可能访问文件系统及系统服务，周期性在后台更新。 */
    private fun refreshResourcesLocked() {
        if (clock() < nextResourceCheckAt || closed) return
        nextResourceCheckAt = clock() + 5_000L
        fileIo.execute { runCatching { resources = Resources(freeBytes(), lowMemory()) } }
    }

    private fun cancelLocked(scopes: Collection<RequestScope>) {
        if (scopes.isEmpty()) return
        val pending = scopes.toList()
        // TLS 连接取消也可能等待底层 I/O，不让生命周期线程承担它。
        try { fileIo.execute { pending.forEach { it.cancel() } } }
        catch (_: RejectedExecutionException) { /* 关闭前已取消所有在途作用域。 */ }
    }

    // ---------- 播放端查询 ----------

    /** 完整文件和索引都在本地时，CDN 地址过期不影响继续播放。 */
    fun meta(id: String, quality: Int): Meta? = synchronized(lock) {
        val key = Key(id, quality)
        val entry = entries[key] ?: return@synchronized null
        if (!entry.meta.done || !covered(entry.ranges, 0, entry.meta.total))
            return@synchronized null
        entry.meta
    }

    /** 起播先选完整缓存：优先当前画质，其次不高于目标的最高画质，最后选已有的最低画质。 */
    fun playbackMeta(id: String, quality: Int): Meta? = synchronized(lock) {
        meta(id, quality)?.let { return@synchronized it }
        val qualities = entries.keys.filter { it.id == id }.map { it.quality }.distinct()
        val ordered = qualities.filter { it <= quality }.sortedDescending() + qualities.filter { it > quality }.sorted()
        ordered.firstNotNullOfOrNull { meta(id, it) }
    }

    /** 部分下载也可供播放端读取；调用方核对索引后，每次 read 再检查区间是否已落盘。 */
    fun readableMeta(id: String, quality: Int): Meta? = synchronized(lock) {
        entries[Key(id, quality)]?.takeIf { it.doneBytes > 0 }?.meta
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
    fun read(id: String, quality: Int, offset: Long, target: ByteArray, targetOffset: Int, length: Int, expected: Meta? = null): Int {
        val key = Key(id, quality)
        val snapshot = synchronized(lock) {
            val entry = entries[key] ?: return 0
            if (expected != null && expected.header !== entry.meta.header) return 0
            val start = offset.coerceIn(0, entry.meta.total)
            val end = (start + length).coerceAtMost(entry.meta.total)
            if (!covered(entry.ranges, start, end - start)) return 0
            Triple(entry, start, (end - start).toInt())
        }
        // 淘汰和重建条目时复用同一文件锁；拿锁后再次核对身份，避免读到另一份密文。
        return synchronized(fileLocks.getOrPut(key) { Any() }) {
            if (synchronized(lock) { entries[key] !== snapshot.first }) return 0
            runCatching {
                RandomAccessFile(dataFile(key), "r").use { file ->
                    file.seek(snapshot.second)
                    file.read(target, targetOffset, snapshot.third)
                }
            }.getOrDefault(-1)
        }
    }

    // ---------- 调度 ----------

    /** 确认某集开始播放后，清理同一部剧中落后至少两集的缓存，其他剧集保持原样。 */
    fun onPlaybackStarted(seriesId: String, episodeIds: List<String>, index: Int) {
        if (index !in episodeIds.indices) return
        synchronized(lock) {
            playingId = episodeIds[index]
            val seriesEpisodes = episodeIds.toSet()
            entries.forEach { (key, entry) ->
                if (key.id in seriesEpisodes && entry.meta.seriesId != seriesId) {
                    entry.meta = entry.meta.copy(seriesId = seriesId)
                    runCatching { saveMeta(key, entry) }
                }
            }
            val obsolete = episodeIds.take((index - 1).coerceAtLeast(0)).toSet()
            want = want.filter { it.id !in obsolete }
            retryAt.keys.removeAll { it.id in obsolete }
            entries.keys.filter { it.id in obsolete }.toList().forEach { key ->
                if (key in active) {
                    discardAfterDownload += key
                    activeScopes[key]?.let { cancelLocked(listOf(it)) }
                } else dropLocked(key)
            }
            evictLocked()
        }
    }

    /** 下载窗口只决定后台任务，不删除窗口外的缓存；返回 false 表示本轮无法调度。 */
    fun schedule(orderedIds: List<String>, quality: Int, parallelism: Int? = null, seriesId: String = ""): Boolean {
        if (closed) { markSkip("缓存池已关闭"); return false }
        val keys = orderedIds.filter { ID_PATTERN.matches(it) }.distinct().map { Key(it, quality) }
        synchronized(lock) {
            if (closed) return false
            refreshResourcesLocked()
            val budget = resources
            if (budget.free < FREE_FLOOR_BYTES || budget.lowMemory) {
                markSkip("磁盘空间或内存不足（可用 ${budget.free / 1048576}MB）")
                onLog("Episode disk cache paused: storage or memory constrained, free=${budget.free / 1048576}MB lowMemory=${budget.lowMemory}")
                return false
            }
            if (keys.isNotEmpty()) markSkip("")
            if (keys != want || downloadSeriesId != seriesId) {
                capacityEvicted.clear()
                onLog("Episode disk cache schedule: series=$seriesId targets=${keys.joinToString(",") { "${it.id}-q${it.quality}" }}")
            }
            want = keys
            downloadSeriesId = seriesId
            parallelism?.let { downloadParallelism = it.coerceIn(0, MAX_PARALLEL) }
            scheduled = true
            cancelExcessDownloadsLocked()
            ensureWorkerLocked()
        }
        return true
    }

    /** 所有剧共用字节上限，每部剧单独最多五条；新下载先预留整集空间。 */
    private fun evictLocked(additionalBytes: Long = 0, seriesId: String = "", episodeId: String = ""): Boolean {
        if (additionalBytes > POOL_LIMIT_BYTES) return false
        while (true) {
            val seriesEpisodes = entries.values.filter { it.meta.seriesId.isNotEmpty() }
                .groupBy { it.meta.seriesId }
                .mapValues { (_, rows) -> rows.mapTo(mutableSetOf()) { it.meta.id } }.toMutableMap()
            if (seriesId.isNotEmpty() && episodeId.isNotEmpty())
                seriesEpisodes.getOrPut(seriesId) { mutableSetOf() }.add(episodeId)
            val overfullSeries = seriesEpisodes.entries.firstOrNull { it.value.size > SERIES_LIMIT_EPISODES }?.key
            if (overfullSeries == null && entries.values.sumOf { it.meta.total } + additionalBytes <= POOL_LIMIT_BYTES)
                return true
            val downloadingIds = active.mapTo(mutableSetOf()) { it.id }
            val victim = entries.keys.filter { it.id !in downloadingIds && it.id != playingId }
                .filter { overfullSeries == null || entries[it]!!.meta.seriesId == overfullSeries }
                .minByOrNull { entries[it]!!.meta.updatedAt } ?: return false
            // 同一集的不同画质占一个集数名额，淘汰时一起回收。
            entries.keys.filter { it.id == victim.id }.toList().forEach { key ->
                if (key in want) capacityEvicted += key
                dropLocked(key)
            }
        }
    }

    private fun dropLocked(key: Key) {
        discardAfterDownload.remove(key)
        retryAt.remove(key)
        entries.remove(key)
        fileTask(key) {
            dataFile(key).delete()
            metaFile(key).delete()
        }
    }

    fun clear() {
        synchronized(lock) {
            want = emptyList(); scheduled = false; retryAt.clear(); capacityEvicted.clear()
            cancelLocked(activeScopes.values)
            setQueued(0); markSkip("缓存已清空")
            entries.keys.toList().forEach { dropLocked(it) }
        }
        // 线程池不 shutdown：clear() 只是清队列，开关重新打开后还要继续用。
        // 在途请求由作用域取消，工作者退出前不会为同一集重复启动任务。
    }

    /** 暂停后台下载但保留已落盘内容：切界面 / 退到后台 / 播放失败收尾都走这里。
     *  以前这些场景调的是 schedule(emptyList(), …, 只留当前集)，会按窗口把下三集连文件一起删掉，
     *  用户切走再回来看到的就是三条进度条全空。 */
    fun pause() {
        synchronized(lock) {
            want = emptyList(); scheduled = false; capacityEvicted.clear()
            cancelLocked(activeScopes.values)
            setQueued(0); markSkip("")
        }
        // 保留已落盘区间；取消在途请求，让当前集立即拿回带宽，之后从缺口续传。
    }

    /** 根据当前集的缓冲余量调整并发；缩减时立即取消低优先级的在途网络请求。 */
    fun setDownloadParallelism(value: Int) {
        synchronized(lock) {
            val next = value.coerceIn(0, MAX_PARALLEL)
            if (closed || downloadParallelism == next) return
            downloadParallelism = next
            cancelExcessDownloadsLocked()
            ensureWorkerLocked()
        }
    }

    private fun cancelExcessDownloadsLocked() {
        val allowed = want.filter { it in active }.take(downloadParallelism).toSet()
        cancelLocked(activeScopes.filterKeys { it !in allowed }.values)
    }

    /** 本地索引损坏时废弃该集缓存；在线地址失效不会调用此方法。 */
    fun invalidate(id: String) {
        synchronized(lock) {
            entries.keys.filter { it.id == id }.toList().forEach { dropLocked(it) }
            retryAt.keys.removeAll { it.id == id }
        }
        runCatching { onProgress(id, -1, 0f) }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            cancelLocked(activeScopes.values)
            entries.forEach { (key, entry) -> saveMeta(key, entry) }
            executor.shutdownNow()
            fileIo.shutdown() // 已排队的最后快照和删除正常收尾，不等待磁盘。
            retiredFiles[directory.absolutePath] = executor to fileIo
        }
    }

    /**
     * 每次 schedule 后补齐下载线程：窗口里还没下完、且没在下载的集，
     * 逐个提交到线程池。同一集不重复提交，缓冲不足时可取消低优先级请求。
     */
    private fun ensureWorkerLocked() {
        if (closed || !scheduled || downloadParallelism == 0) return
        val now = clock()
        val pending = want.filter { key ->
            key !in active && key !in capacityEvicted && (retryAt[key] ?: 0L) <= now && needsDownloadLocked(key)
        }
        // 未开始下载的集按优先级补到并发上限（下 1/2/3 集同时开工）
        pending.take((downloadParallelism - active.size).coerceAtLeast(0)).forEach { key ->
            val scope = RequestScope()
            active += key
            activeScopes[key] = scope
            executor.execute { downloadWorker(key, scope) }
        }
        // 队列长度 = 还没下完的集数（含正在下的），供日志/摘要显示
        setQueued(want.count { it !in capacityEvicted && needsDownloadLocked(it) })
        // 目标窗口全部下完时把摘要恢复成空串（= 正常）。这里曾经写「队列已清空，等待下一次
        // 播放调度」，界面会照着显示成还在排队，和「已全部缓存」正好相反。
        if (capacityEvicted.isNotEmpty()) markSkip("缓存池已满，部分目标等待播放推进后继续缓存")
        else if (queueIsEmptyLocked() && skipReason.isNotEmpty() && retryAt.isEmpty()) markSkip("")
    }

    /** 这一集是否还需要下载：没有条目、没标记完成，或者有区间缺口。 */
    private fun needsDownloadLocked(key: Key): Boolean {
        val entry = entries[key] ?: return true
        return !entry.meta.done || !covered(entry.ranges, 0, entry.meta.total)
    }

    private fun queueIsEmptyLocked(): Boolean = want.none { needsDownloadLocked(it) }

    // ---------- 下载线程（并发，每个线程一条集） ----------

    /** 单个下载工作者：一次只负责一集，下完就退出，由下一次 schedule 决定要不要继续。 */
    private fun downloadWorker(key: Key, scope: RequestScope) {
        try {
            scope.run { downloadEpisode(key) }
        } catch (cancelled: InterruptedException) {
            // 线程池关闭、暂停调度或切出窗口：把已落盘区间写回元数据，
            // 之后再排到这一集时从缺口续传，已经下好的部分不会白费。
            synchronized(lock) {
                val entry = entries[key]
                if (entry != null) runCatching { saveMeta(key, entry) }
            }
        } catch (problem: Throwable) {
            if (closed) return
            if (scope.isCancelled) {
                synchronized(lock) { entries[key]?.let { runCatching { saveMeta(key, it) } } }
                return // 带宽让路或页面暂停是正常取消，不记失败、不进入十秒退避。
            }
            // 地址过期：未完成的条目需要重新取址。
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
                if (closed || scope.isCancelled) entries[key]?.let { runCatching { saveMeta(key, it) } }
                active -= key
                activeScopes.remove(key)
                if (key in discardAfterDownload) dropLocked(key)
                setQueued(if (scheduled) want.count { it !in capacityEvicted && needsDownloadLocked(it) } else 0)
                // 这一集收工后立刻补位：窗口里还有没下的集就继续开工
                ensureWorkerLocked()
            }
        }
    }

    private fun downloadEpisode(key: Key) {
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
    }

    /** 缺元数据则解析地址并整段 prepare（约两三跳网络），之后按 4MiB 段续传。锁外执行。 */
    private fun openRow(key: Key): Row? {
        val seriesId = synchronized(lock) {
            entries[key]?.let { return Row(key, it, dataFile(key), metaFile(key)) }
            if (key !in want) return null
            downloadSeriesId
        }
        val info = runCatching { resolver(key.id, key.quality) }.getOrElse {
            if (RequestScope.current()?.isCancelled == true || Thread.currentThread().isInterrupted)
                throw InterruptedException("下载已取消")
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
                remote.total, snapshot.header, snapshot.samples, clock(), false, seriesId)
            val entry = Entry(meta, emptyList())
            val initialWrite = synchronized(lock) {
                if (closed || RequestScope.current()?.isCancelled == true || entries.containsKey(key) || key !in want) return null
                if (!evictLocked(additionalBytes = meta.total, seriesId = seriesId, episodeId = key.id)) {
                    markSkip("缓存池容量不足，等待播放推进后继续缓存")
                    return null
                }
                entries[key] = entry
                // 新条目必须等先前同名文件的删除及本次初始快照完成，再写视频数据。
                saveMeta(key, entry)
            }
            initialWrite?.get() // 仅下载工作线程等待，绝不在状态锁或界面线程等待。
            return Row(key, entry, dataFile(key), metaFile(key))
        } catch (problem: Throwable) {
            if (RequestScope.current()?.isCancelled == true || Thread.currentThread().isInterrupted)
                throw InterruptedException("下载已取消")
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
        // 单路下载用 1MiB，尽早提供可播放区间、减少让路时丢弃的在途数据；宽裕时用 4MiB。
        var pendingMeta = 0
        val fileLock = fileLocks.getOrPut(row.key) { Any() }
        var at = 0L
        while (at < total) {
            if (Thread.currentThread().isInterrupted || closed || RequestScope.current()?.isCancelled == true)
                throw InterruptedException("下载已取消")
            synchronized(lock) {
                if (entries[row.key] !== row.entry) throw InterruptedException("下载已取消")
                // 这一集已经滑出下载窗口（用户切到别的集了）：立刻让出线程槽位给新的下 1/2/3 集。
                // 否则连切几集后三个线程还在下旧集，新窗口只能在后面等——就是「老是卡在排队」。
                // 已落盘的部分保留在磁盘上（meta 里有区间），以后排到时从缺口续传。
                if (row.key !in want) throw InterruptedException("已移出下载窗口")
            }
            val segment = synchronized(lock) { (if (downloadParallelism == 1) 1L else 4L) * 1024 * 1024 }
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
            val bytes = fetch(row.entry.meta, rangeStart, rangeEnd - 1)
            val ratio = synchronized(fileLock) {
                synchronized(lock) {
                    if (closed || entries[row.key] !== row.entry) throw InterruptedException("下载已取消")
                }
                RandomAccessFile(row.file, "rw").use { file ->
                    file.seek(rangeStart)
                    file.write(bytes)
                    file.fd.sync()
                }
                synchronized(lock) {
                    if (closed || entries[row.key] !== row.entry) throw InterruptedException("下载已取消")
                    row.entry.ranges = mergeRanges(row.entry.ranges, longArrayOf(rangeStart, rangeEnd))
                    row.entry.doneBytes = row.entry.ranges.sumOf { it[1] - it[0] }
                    // 比例在短锁内计算，读写文件时不占用状态锁。
                    if (total <= 0) 0f else (row.entry.doneBytes.toFloat() / total).coerceIn(0f, 1f)
                }
            }
            runCatching { onProgress(row.key.id, row.key.quality, ratio) }
            if (++pendingMeta >= COMPACT_EVERY) { pendingMeta = 0; runCatching { saveMeta(row.key, row.entry) } }
            at = end
        }
        if (pendingMeta > 0) runCatching { saveMeta(row.key, row.entry) }
    }

    private fun fetch(meta: Meta, start: Long, end: Long): ByteArray {
        val request = Request.Builder().url(meta.url).header("User-Agent", VendorConstants.VIDEO_UA)
            .header("Referer", "https://novel.snssdk.com/").header("Range", "bytes=$start-$end")
            .header("Accept-Encoding", "identity").build()
        val call = http.newCall(request)
        try {
            return RequestScope.execute(call) { response ->
                if (response.code != 206) throw IOException("视频服务器不支持分段读取（HTTP ${response.code}）")
                val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                    ?: throw IOException("视频分段响应异常")
                val (begin, finish, total) = match.destructured
                if (begin.toLong() != start || finish.toLong() != end || total.toLong() != meta.total)
                    throw IOException("视频分段范围不匹配")
                val body = response.body ?: throw IOException("视频分段为空")
                // 只把完整收到的区间标记为可读，避免短响应留下被误判为已缓存的空洞。
                body.source().readByteArray(end - start + 1)
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
