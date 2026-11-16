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

/** 手动离线库独立于自动预载池，任务随进程保留，页面退出不取消。所有索引和文件操作均在后台。 */
class OfflineDownloads private constructor(context: Context) {
    data class Episode(val id: String, val number: Int, val size: Long, val quality: String)
    data class Item(
        val series: Series, val episodeIds: List<String>, val selected: Set<String>,
        val downloaded: List<Episode> = emptyList(), val running: Boolean = false,
        val current: Int = 0, val bytes: Long = 0, val totalBytes: Long = 0, val message: String = "",
    ) {
        val size get() = downloaded.sumOf { it.size }
        val pending get() = selected - downloaded.map { it.id }.toSet()
        val progress get() = if (selected.isEmpty()) 0 else
            ((downloaded.count { it.id in selected } + if (totalBytes > 0) bytes.toDouble() / totalBytes else 0.0) * 1000 / selected.size).toInt().coerceIn(0, 1000)
    }
    private class Job {
        val scope = RequestScope()
        @Volatile var video: RemoteVideo? = null
        fun cancel() { scope.cancel(); video?.close() }
    }
    private val app = context.applicationContext
    private val root = File(app.getExternalFilesDir(null) ?: app.filesDir, "offline-library")
    private val index = File(root, "library.json")
    private val control = Executors.newSingleThreadExecutor { Thread(it, "offline-library") }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "offline-download") }
    private val metadata = Executors.newSingleThreadExecutor { Thread(it, "offline-details") }
    private val main = Handler(Looper.getMainLooper())
    private val repository by lazy { ContentRepository(signer = SigningSettings.createSigner(SharedPrefsSignerStore(app))) }
    private val records = linkedMapOf<String, Item>()
    private val jobs = mutableMapOf<String, Job>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile var items: List<Item> = emptyList(); private set
    @Volatile var loaded = false; private set
    @Volatile var error = ""; private set

    init { control.execute { load() } }
    fun observe(listener: () -> Unit) { listeners += listener }
    fun unobserve(listener: () -> Unit) { listeners -= listener }
    private fun publish() {
        items = records.values.toList()
        main.post { listeners.forEach { it() } }
    }
    private fun file(seriesId: String, episodeId: String) = File(File(root, seriesId), "$episodeId.mp4")
    /** 只定位已完成条目；文件的存在性由播放工作线程检查。 */
    fun lookup(episodeId: String): File? = items.firstOrNull { item -> item.downloaded.any { it.id == episodeId } }
        ?.let { file(it.series.id, episodeId) }

    fun detail(series: Series, done: (Result<Detail>) -> Unit) {
        metadata.execute {
            val result = runCatching { repository.seriesDetail(series.id) }
            main.post { done(result) }
        }
    }
    fun start(detail: Detail, selected: Set<String>) = control.execute {
        if (!loaded) return@execute
        val id = detail.series.id
        val old = records[id]
        val ids = (old?.episodeIds.orEmpty() + detail.episodes).distinct()
        // 同一剧的接口顺序决定集号，继续下载沿用最初保存的分集表。
        val savedIds = if (old == null) detail.episodes else ids
        records[id] = Item(detail.series, savedIds,
            old?.selected.orEmpty() + selected.filter { it in savedIds }, old?.downloaded.orEmpty())
        jobs.remove(id)?.cancel()
        if (persist()) launch(id)
    }
    fun resume(id: String) = control.execute { if (loaded) launch(id) }
    fun pause(id: String) = control.execute {
        jobs.remove(id)?.cancel()
        records[id]?.let { records[id] = it.copy(running = false, current = 0, bytes = 0, totalBytes = 0, message = "已暂停，可继续下载") }
        persist()
    }
    fun remove(id: String) = control.execute {
        val old = records.remove(id) ?: return@execute
        jobs.remove(id)?.cancel()
        if (persist()) worker.execute { File(root, id).deleteRecursively() }
        else { records[id] = old.copy(running = false); publish() }
    }

    private fun launch(id: String) {
        if (jobs.containsKey(id)) return
        val item = records[id] ?: return
        if (item.pending.isEmpty()) { publish(); return }
        val job = Job(); jobs[id] = job
        records[id] = item.copy(running = true, message = "等待下载", current = 0, bytes = 0, totalBytes = 0)
        publish()
        worker.execute {
            val failures = mutableListOf<String>()
            for (episodeId in item.episodeIds.filter { it in item.pending }) {
                if (job.scope.isCancelled) break
                val number = item.episodeIds.indexOf(episodeId) + 1
                try {
                    val episode = download(id, episodeId, number, job)
                    control.submit {
                        if (jobs[id] === job) {
                            val current = records.getValue(id)
                            records[id] = current.copy(downloaded = (current.downloaded.filterNot { it.id == episodeId } + episode).sortedBy { it.number },
                                current = 0, bytes = 0, totalBytes = 0, message = "")
                            if (!persist()) throw IOException("离线索引保存失败，请重试")
                        }
                    }.get()
                } catch (problem: Exception) {
                    if (job.scope.isCancelled) break
                    failures += "第${number}集：${problem.cause?.message ?: problem.message ?: "下载失败"}"
                    RuntimeLog.add("Offline episode=$episodeId failed: ${problem.message}")
                }
            }
            control.execute {
                if (jobs[id] === job) {
                    jobs.remove(id)
                    records[id]?.let { records[id] = it.copy(running = false, current = 0, bytes = 0, totalBytes = 0,
                        message = if (failures.isEmpty()) "" else failures.joinToString("；").take(240)) }
                    persist()
                }
            }
        }
    }

    /** 复用播放器的 Range 读取和 CENC 解密，逐块写入明文 MP4，密钥只留内存。 */
    private fun download(seriesId: String, id: String, number: Int, job: Job): Episode {
        val target = file(seriesId, id)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "无法创建离线目录" }
        val part = File(target.path + ".part")
        val fingerprint = File(target.path + ".resume")
        var failure: Exception? = null
        repeat(3) { attempt ->
            job.scope.ensureActive()
            try {
                return job.scope.run {
                    val info = repository.stream(id, 1080, refreshModel = attempt > 0)
                    RemoteVideo(repository.http, info, chunkBytes = 256 * 1024, aheadBytes = 0).use { video ->
                        job.video = video
                        job.scope.ensureActive()
                        video.setPlaybackActive(false)
                        video.prepare()
                        val digest = MessageDigest.getInstance("SHA-256")
                        digest.update(video.preparedSnapshot().header)
                        digest.update(info.key ?: byteArrayOf())
                        val signature = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } + ":${video.total}"
                        if (!fingerprint.isFile || fingerprint.readText() != signature || part.length() > video.total) {
                            RandomAccessFile(part, "rw").use { it.setLength(0) }
                            fingerprint.writeText(signature)
                        }
                        val needed = video.total - part.length() + 50L * 1024 * 1024
                        if (root.usableSpace < needed) throw IOException("存储空间不足，请清理后继续下载")
                        RandomAccessFile(part, "rw").use { out ->
                            var at = out.length(); out.seek(at)
                            var lastUpdate = 0L
                            while (at < video.total) {
                                job.scope.ensureActive()
                                val bytes = video.read(at, minOf(256 * 1024L, video.total - at).toInt())
                                if (bytes.isEmpty()) throw IOException("视频分块提前结束")
                                out.write(bytes); at += bytes.size
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate >= 250 || at == video.total) {
                                    lastUpdate = now
                                    val downloaded = at
                                    control.execute {
                                        if (jobs[seriesId] === job) {
                                            records[seriesId]?.let { records[seriesId] = it.copy(current = number, bytes = downloaded, totalBytes = video.total, message = "") }
                                            publish()
                                        }
                                    }
                                }
                            }
                            out.fd.sync()
                        }
                        job.scope.ensureActive()
                        check(part.renameTo(target)) { "离线文件保存失败" }
                        fingerprint.delete()
                        Episode(id, number, target.length(), info.quality)
                    }
                }
            } catch (problem: Exception) {
                failure = problem
                job.scope.ensureActive()
            } finally { job.video = null }
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
                    .put("selected", JSONArray(item.selected.toList())).put("downloaded", downloaded))
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
                records[series.id] = Item(series, episodes, ids("selected").filter { it in episodes }.toSet(), downloaded)
            }
            loaded = true
        } catch (problem: Exception) {
            error = "离线记录读取失败：${problem.message}"
        }
        publish()
    }
    companion object {
        private val ID = Regex("[0-9]{1,30}")
        @Volatile private var instance: OfflineDownloads? = null
        fun get(context: Context): OfflineDownloads = instance ?: synchronized(this) {
            instance ?: OfflineDownloads(context).also { instance = it }
        }
    }
}
