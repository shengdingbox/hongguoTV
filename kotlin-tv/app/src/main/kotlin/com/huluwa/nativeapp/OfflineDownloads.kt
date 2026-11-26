// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hongguotv.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** 手动离线库独立于自动预载池，任务随进程保留，页面退出不取消。所有索引和文件操作均在后台。 */
class OfflineDownloads private constructor(context: Context) {
    data class Episode(val id: String, val number: Int, val size: Long, val quality: String)
    data class Transfer(val id: String, val number: Int, val bytes: Long = 0, val totalBytes: Long = 0)
    data class Item(
        val series: Series, val episodeIds: List<String>, val selected: Set<String>,
        val downloaded: List<Episode> = emptyList(), val running: Boolean = false,
        val transfers: List<Transfer> = emptyList(), val message: String = "",
        /** 最后一次开始缓存或收到下载进度的时刻，列表按它倒序，新下载的排在最前。 */
        val updatedAt: Long = System.currentTimeMillis(),
    ) {
        val size get() = downloaded.sumOf { it.size }
        val pending get() = selected - downloaded.map { it.id }.toSet()
        val progress: Int get() {
            if (selected.isEmpty()) return 0
            val done = downloaded.map { it.id }.toSet()
            val partial = transfers.filter { it.id in selected && it.id !in done }.sumOf {
                if (it.totalBytes > 0) (it.bytes.toDouble() / it.totalBytes).coerceIn(0.0, 1.0) else 0.0
            }
            return ((done.count { it in selected } + partial) * 1000 / selected.size).toInt().coerceIn(0, 1000)
        }
    }
    private data class Key(val seriesId: String, val episodeId: String)
    private class Run {
        val attempted = mutableSetOf<String>()
        val failures = linkedMapOf<String, String>()
    }
    private class Job(val key: Key, val run: Run, val number: Int, val albumEpisode: Boolean) {
        val scope = RequestScope()
        @Volatile var video: OfflineVideo? = null
        fun cancel() { scope.cancel(); video?.close() }
    }
    private val app = context.applicationContext
    private val root = File(app.getExternalFilesDir(null) ?: app.filesDir, "offline-library")
    private val index = File(root, "library.json")
    private val control = Executors.newSingleThreadExecutor { Thread(it, "offline-library") }
    private val threadCount = AtomicInteger()
    private val worker = Executors.newFixedThreadPool(MAX_CONCURRENCY) { Thread(it, "offline-download-${threadCount.incrementAndGet()}") }
    private val metadata = Executors.newSingleThreadExecutor { Thread(it, "offline-details") }
    private val main = Handler(Looper.getMainLooper())
    private val signerStore by lazy { SharedPrefsSignerStore(app) }
    private val playbackAlgorithms by lazy { PlaybackAlgorithms.shared(app, signerStore) }
    private val repository: () -> ContentRepository = {
        ContentRepository(signer = playbackAlgorithms.signer, playbackAlgorithms = playbackAlgorithms)
    }
    private val records = linkedMapOf<String, Item>()
    // 以下调度状态只在 control 线程修改；取消中的任务收尾前仍占用槽位和文件。
    private val runs = linkedMapOf<String, Run>()
    private val jobs = mutableMapOf<Key, Job>()
    private val removing = mutableSetOf<String>()
    private val waitingStarts = mutableMapOf<String, Pair<Detail, Set<String>>>()
    private val spaceLock = Any()
    private val reservations = mutableMapOf<Job, Long>()
    val concurrency get() = StartupSettings.int(CONCURRENCY_KEY, DEFAULT_CONCURRENCY).coerceIn(1, MAX_CONCURRENCY)
    private var parallelism = concurrency
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile var items: List<Item> = emptyList(); private set
    @Volatile var loaded = false; private set
    @Volatile var error = ""; private set

    init { control.execute { load() } }
    fun observe(listener: () -> Unit) { listeners += listener }
    fun unobserve(listener: () -> Unit) { listeners -= listener }
    private fun publish() {
        // 最后下载的排在最前；时间相同（同一毫秒内批量创建）时按剧集 id 保证顺序稳定。
        items = records.values.sortedWith(compareByDescending<Item> { it.updatedAt }.thenBy { it.series.id })
        main.post { listeners.forEach { it() } }
    }
    private fun file(seriesId: String, episodeId: String) = File(File(root, seriesId), "$episodeId.mp4")
    /** 只定位已完成条目；文件的存在性由播放工作线程检查。 */
    fun lookup(episodeId: String): File? = items.firstOrNull { item -> item.downloaded.any { it.id == episodeId } }
        ?.let { file(it.series.id, episodeId) }

    fun detail(series: Series, done: (Result<Detail>) -> Unit) {
        metadata.execute {
            val result = runCatching { repository().seriesDetail(series.id) }
            main.post { done(result) }
        }
    }
    /** 增加并发立即补满槽位，降低并发时让在途分集完成，保留已有下载进度。 */
    fun setConcurrency(value: Int) {
        val next = value.coerceIn(1, MAX_CONCURRENCY)
        StartupSettings.set(CONCURRENCY_KEY, next)
        control.execute { parallelism = next; schedule(); publish() }
    }

    fun start(detail: Detail, selected: Set<String>) = control.execute {
        if (!loaded) return@execute
        startInternal(detail, selected)
    }
    private fun startInternal(detail: Detail, selected: Set<String>) {
        val id = detail.series.id
        if (id in removing) {
            val old = waitingStarts[id]
            waitingStarts[id] = detail.copy(episodes = (old?.first?.episodes.orEmpty() + detail.episodes).distinct()) to
                (old?.second.orEmpty() + selected)
            return
        }
        val old = records[id]
        // 同一剧继续缓存保留最初的分集顺序和在途任务，只追加新目标。
        val ids = (old?.episodeIds.orEmpty() + detail.episodes).distinct()
        val targets = old?.selected.orEmpty() + selected.filter { it in ids }
        // 刷新时间，让刚发起缓存的剧排到列表最前。
        records[id] = old?.copy(series = detail.series, episodeIds = ids, selected = targets,
            updatedAt = System.currentTimeMillis())
            ?: Item(detail.series, ids, targets)
        if (persist()) launch(id) else {
            stop(id, "离线记录保存失败，请重试")
            schedule(); publish()
        }
    }
    fun resume(id: String) = control.execute {
        if (!loaded) return@execute
        // 继续下载同样算一次「最近下载」，刷新时间后重新排到前面。
        records[id]?.let { records[id] = it.copy(updatedAt = System.currentTimeMillis()) }
        if (persist()) launch(id)
    }
    fun pause(id: String) = control.execute {
        waitingStarts.remove(id)
        stop(id, "已暂停，可继续下载")
        persist(); schedule(); publish()
    }
    private fun stop(id: String, message: String) {
        runs.remove(id)
        jobs.values.filter { it.key.seriesId == id }.forEach { it.cancel() }
        records[id]?.let { records[id] = it.copy(running = false, transfers = emptyList(), message = message) }
    }
    fun remove(id: String) = control.execute {
        waitingStarts.remove(id)
        val old = records.remove(id) ?: return@execute
        runs.remove(id)
        jobs.values.filter { it.key.seriesId == id }.forEach { it.cancel() }
        if (persist()) {
            removing += id
            cleanRemoved(id)
        } else {
            records[id] = old.copy(running = false, transfers = emptyList())
            publish()
        }
        schedule(); publish()
    }
    /** 取消中的任务全部退出后才能删目录；同剧重新缓存也在清理后启动。 */
    private fun cleanRemoved(id: String) {
        if (id !in removing || jobs.keys.any { it.seriesId == id }) return
        File(root, id).deleteRecursively()
        removing.remove(id)
        waitingStarts.remove(id)?.let { (detail, selected) -> startInternal(detail, selected) }
    }

    private fun launch(id: String) {
        val item = records[id] ?: return
        if (item.pending.isEmpty()) { publish(); return }
        val run = runs.getOrPut(id) { Run() }
        run.attempted.removeAll(run.failures.keys)
        run.failures.clear()
        records[id] = item.copy(running = true, message = "")
        schedule(); publish()
    }

    /** 全库共用并发上限，按剧轮转分配槽位，同一分集在旧任务退出前不会再次启动。 */
    private fun schedule() {
        var finished = false
        runs.entries.toList().forEach { (id, run) ->
            val item = records[id]
            val hasActive = jobs.values.any { it.key.seriesId == id && it.run === run }
            if (!hasActive && (item == null || item.pending.none { it !in run.attempted })) {
                runs.remove(id)
                if (item != null) records[id] = item.copy(running = false, transfers = emptyList(),
                    message = run.failures.values.joinToString("；").take(240))
                finished = true
            }
        }
        if (finished) persist()
        while (jobs.size < parallelism) {
            val next = runs.entries.firstNotNullOfOrNull { (id, run) ->
                val item = records[id] ?: return@firstNotNullOfOrNull null
                val pending = item.pending
                item.episodeIds.firstOrNull { it in pending && it !in run.attempted && Key(id, it) !in jobs }
                    ?.let { Triple(id, run, it) }
            } ?: break
            val (id, run, episodeId) = next
            val item = records.getValue(id)
            val key = Key(id, episodeId)
            // 在调度线程固定类型，后台取址及重试不依赖详情仓库或可变下载记录。
            val job = Job(key, run, item.episodeIds.indexOf(episodeId) + 1, item.series.isAlbum)
            run.attempted += episodeId
            runs.remove(id); runs[id] = run
            jobs[key] = job
            records[id] = item.copy(transfers = (item.transfers + Transfer(episodeId, job.number)).sortedBy { it.number })
            worker.execute {
                val result = runCatching { download(id, episodeId, job.number, job) }
                control.execute { complete(job, result) }
            }
        }
    }

    private fun complete(job: Job, result: Result<Episode>) {
        if (jobs[job.key] !== job) return
        jobs.remove(job.key)
        val id = job.key.seriesId
        val item = records[id]
        if (item != null && id !in removing) {
            // 暂停恰好发生在落盘完成之后时，完成的文件仍然加入离线库。
            val episode = result.getOrNull()
            records[id] = item.copy(transfers = item.transfers.filterNot { it.id == job.key.episodeId },
                downloaded = if (episode == null) item.downloaded else
                    (item.downloaded.filterNot { it.id == episode.id } + episode).sortedBy { it.number })
            if (episode != null) {
                if (!persist()) stop(id, "离线记录保存失败，请重试")
            } else if (runs[id] === job.run && !job.scope.isCancelled) {
                val problem = result.exceptionOrNull()
                val reason = "第${job.number}集：${problem?.message ?: "下载失败"}"
                job.run.failures[job.key.episodeId] = reason
                RuntimeLog.add("Offline episode=${job.key.episodeId} failed: ${problem?.message}")
            }
        }
        cleanRemoved(id)
        schedule(); publish()
    }

    /** 各分集预留尚未写入的空间，避免多路同时检查时重复使用同一份剩余容量。 */
    private fun reserve(job: Job, remaining: Long) = synchronized(spaceLock) {
        val other = reservations.filterKeys { it !== job }.values.sum()
        if (root.usableSpace < other + remaining + 50L * 1024 * 1024)
            throw IOException("存储空间不足，请清理后继续下载")
        reservations[job] = remaining
    }

    /** 离线专用大分块传输；与播放器共用 CENC 索引，密钥只留内存。 */
    private fun download(seriesId: String, id: String, number: Int, job: Job): Episode {
        val target = file(seriesId, id)
        val parent = target.parentFile!!
        check(parent.isDirectory || parent.mkdirs() || parent.isDirectory) { "无法创建离线目录" }
        val part = File(target.path + ".part")
        val fingerprint = File(target.path + ".resume")
        var failure: Exception? = null
        repeat(3) { attempt ->
            job.scope.ensureActive()
            try {
                return job.scope.run {
                    val resolveStarted = System.nanoTime()
                    val activeRepo = repository()
                    val info = activeRepo.stream(id, 1080, refreshModel = attempt > 0, albumEpisode = job.albumEpisode)
                    RuntimeLog.add("Offline episode=$id sourceMs=${(System.nanoTime() - resolveStarted) / 1_000_000} attempt=${attempt + 1}")
                    OfflineVideo(activeRepo.http, info, onLog = { RuntimeLog.add("Offline episode=$id $it") }).use { video ->
                        job.video = video
                        job.scope.ensureActive()
                        video.prepare()
                        val digest = MessageDigest.getInstance("SHA-256")
                        digest.update(video.preparedSnapshot().header)
                        digest.update(info.key ?: byteArrayOf())
                        val signature = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } + ":${video.total}"
                        if (!fingerprint.isFile || fingerprint.readText() != signature || part.length() > video.total) {
                            RandomAccessFile(part, "rw").use { it.setLength(0) }
                            fingerprint.writeText(signature)
                        }
                        reserve(job, video.total - part.length())
                        val transferStarted = System.nanoTime()
                        val resumedBytes = part.length()
                        RandomAccessFile(part, "rw").use { out ->
                            video.writeTo(out, onWritten = { count ->
                                synchronized(spaceLock) { reservations[job] = (reservations[job] ?: 0L).minus(count).coerceAtLeast(0L) }
                            }, onProgress = { downloaded, total ->
                                control.execute {
                                    if (jobs[job.key] === job && runs[seriesId] === job.run && !job.scope.isCancelled) {
                                        records[seriesId]?.let { item ->
                                            records[seriesId] = item.copy(transfers = item.transfers.map {
                                                if (it.id == id) it.copy(bytes = downloaded, totalBytes = total) else it
                                            })
                                        }
                                        publish()
                                    }
                                }
                            })
                            out.fd.sync()
                        }
                        val elapsedMs = ((System.nanoTime() - transferStarted) / 1_000_000).coerceAtLeast(1)
                        val transferred = video.total - resumedBytes
                        RuntimeLog.add("Offline episode=$id bytes=$transferred transferMs=$elapsedMs speedKiBps=${transferred * 1000 / elapsedMs / 1024}")
                        job.scope.ensureActive()
                        check(part.renameTo(target)) { "离线文件保存失败" }
                        fingerprint.delete()
                        Episode(id, number, target.length(), info.quality)
                    }
                }
            } catch (problem: Exception) {
                failure = problem
                job.scope.ensureActive()
            } finally {
                job.video = null
                synchronized(spaceLock) { reservations.remove(job) }
            }
        }
        throw failure ?: IOException("下载失败")
    }

    private fun persist(): Boolean {
        return try {
            check(root.isDirectory || root.mkdirs()) { "无法创建离线目录" }
            val rows = JSONArray()
            records.values.forEach { item ->
                val downloaded = JSONArray()
                item.downloaded.forEach { e -> downloaded.put(JSONObject().put("id", e.id).put("number", e.number).put("size", e.size).put("quality", e.quality)) }
                rows.put(JSONObject().put("series", item.series.toJson()).put("episodes", JSONArray(item.episodeIds))
                    .put("selected", JSONArray(item.selected.toList())).put("downloaded", downloaded)
                    .put("updatedAt", item.updatedAt))
            }
            val temporary = File(root, "library.tmp")
            temporary.writeText(rows.toString())
            check(temporary.renameTo(index)) { "离线索引保存失败" }
            error = ""; publish(); true
        } catch (problem: Exception) {
            error = problem.message ?: "离线索引保存失败"; publish(); false
        }
    }
    private fun load() {
        try {
            val rows = if (index.isFile) JSONArray(index.readText()) else JSONArray()
            for (i in 0 until rows.length()) {
                val o = rows.getJSONObject(i)
                val series = Series.fromJson(o.getJSONObject("series"))
                if (!series.id.matches(ID)) continue
                fun ids(key: String): List<String> {
                    val array = o.getJSONArray(key)
                    return (0 until array.length()).map { array.getString(it) }.filter { it.matches(ID) }.distinct()
                }
                val episodes = ids("episodes")
                val saved = o.getJSONArray("downloaded")
                val downloaded = (0 until saved.length()).mapNotNull { j ->
                    val e = saved.getJSONObject(j); val id = e.getString("id"); val number = e.getInt("number"); val size = e.getLong("size")
                    if (id !in episodes || episodes.indexOf(id) + 1 != number || size <= 0 || !file(series.id, id).isFile || file(series.id, id).length() != size) null
                    else Episode(id, number, size, e.optString("quality"))
                }
                // 旧版本索引没有 updatedAt：按原下标给一个递增的兜底值，
                // 既保持旧的相对顺序，又排在后续新下载的条目之后。
                val updatedAt = if (o.has("updatedAt")) o.optLong("updatedAt", 0L) else i.toLong()
                records[series.id] = Item(series, episodes, ids("selected").filter { it in episodes }.toSet(), downloaded,
                    updatedAt = updatedAt)
            }
            loaded = true
        } catch (problem: Exception) {
            error = "离线记录读取失败：${problem.message}"
        }
        publish()
    }
    companion object {
        const val MAX_CONCURRENCY = 5
        const val DEFAULT_CONCURRENCY = 3
        private const val CONCURRENCY_KEY = "offlineDownloadConcurrency"
        private val ID = Regex("[0-9]{1,30}")
        @Volatile private var instance: OfflineDownloads? = null
        fun get(context: Context): OfflineDownloads = instance ?: synchronized(this) {
            instance ?: OfflineDownloads(context).also { instance = it }
        }
    }
}
