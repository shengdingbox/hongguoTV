// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.util.PriorityQueue
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Page-owned artwork requests. Public methods are called on the UI thread; decoding stays on workers. */
class ArtworkLoader(context: Context) {
    companion object {
        const val PRIORITY_FOCUSED = 0
        const val PRIORITY_VISIBLE = 1
        const val PRIORITY_PREFETCH = 2
        private const val MAX_PENDING = 24
        private const val MAX_BYTES = 4 * 1024 * 1024
        // 位图不持有 Activity；旋转重建后继续复用，实际内存压力仍由 trimMemory 回收。
        private var sharedMemory: LruCache<Key, Bitmap>? = null
        private var sharedDisk: ArtworkCache? = null
    }

    private data class Key(val url: String, val width: Int, val height: Int)
    private class Binding(view: ImageView, val owner: Long, var url: String, val cacheUrl: String, var priority: Int,
                          val width: Int, val height: Int) {
        val view = WeakReference(view)
        var key: Key? = null
        var applied: Key? = null
        var failed: Key? = null
        var failures = 0
        var retryAfter = 0L
        lateinit var layout: View.OnLayoutChangeListener
        lateinit var attach: View.OnAttachStateChangeListener
    }
    private class Job(val key: Key, val url: String, val owner: Long, var priority: Int, val order: Long) : Comparable<Job> {
        val listeners = linkedSetOf<Binding>()
        val prefetchers = mutableSetOf<Any>()
        @Volatile var cancelled = false
        @Volatile var call: Call? = null
        // PriorityQueue 的比较器构造器要 API 24，这里用自然序（API 1 就有）
        override fun compareTo(other: Job) = compareValuesBy(this, other, { it.priority }, { it.order })
    }

    private val main = Handler(Looper.getMainLooper())
    private val activityManager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val lowRam = activityManager?.isLowRamDevice == true
    // 普通设备最多用应用内存预算的 1/8，范围 8–32 MiB；低内存设备保留 4 MiB。
    private val cacheBytes = (if (lowRam) 4 else ((activityManager?.memoryClass ?: 128) / 8).coerceIn(8, 32)) * 1024 * 1024
    private val cache = synchronized(Companion) {
        sharedMemory ?: object : LruCache<Key, Bitmap>(cacheBytes) {
            override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
        }.also { sharedMemory = it }
    }
    // v2：代取链路现在落盘的是转码后的 JPEG（v1 缓存里可能存着手机解不了的 HEIC 原始字节），升版本作废旧缓存
    private val disk = synchronized(Companion) {
        sharedDisk ?: ArtworkCache(File(context.applicationContext.cacheDir, "artwork-v2")).also { sharedDisk = it }
    }
    // This client owns its dispatcher and pool; cancelling artwork never cancels video or catalog calls.
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private val workers = Executors.newFixedThreadPool(if (lowRam) 1 else 2) { runnable ->
        Thread(runnable, "hongguotv-artwork").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    // 本地读盘和解码不等待网络线程，已有封面不会被慢下载堵住。
    private val cacheWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hongguotv-artwork-cache").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val signal = Object()
    private val cachedPending = PriorityQueue<Job>()
    private val pending = PriorityQueue<Job>()
    private val jobs = mutableMapOf<Key, Job>()
    private val bindings = WeakHashMap<ImageView, Binding>()
    private class PrefetchWindow(view: View, val urls: Map<Key, String>) {
        val view = WeakReference(view)
        val attempted = mutableSetOf<Key>()
    }
    private val prefetchWindows = WeakHashMap<Any, PrefetchWindow>()
    // 仅固定返回列表所需的一屏位图，防止详情大图挤掉它们后返回时重新读盘、解码。
    private val returnBitmaps = linkedMapOf<Key, Bitmap>()
    private var owner = 0L
    private var order = 0L
    @Volatile private var closed = false
    private var viewportRefreshPosted = false
    private val viewportRefresh = Runnable { viewportRefreshPosted = false; refillVisible() }
    private val retryVisible = Runnable { refillVisible() }

    init {
        cacheWorker.execute { work(localOnly = true) }
        repeat(if (lowRam) 1 else 2) { workers.execute { work(localOnly = false) } }
    }

    /** Size defaults to the laid-out content bounds, including the detail page's portrait artwork. */
    fun load(view: ImageView, url: String, priority: Int = PRIORITY_VISIBLE, widthPx: Int = 0, heightPx: Int = 0,
             keepCurrentImage: Boolean = false) {
        if (closed) return
        // 同一剧集的刷新结果暂缺封面时，保留旧图和仍在进行的请求。
        if (keepCurrentImage && url.isBlank()) return
        val cacheUrl = ArtworkCache.identity(url)
        val previous = bindings[view]
        if (previous != null && previous.owner == owner && previous.cacheUrl == cacheUrl && previous.width == widthPx && previous.height == heightPx) {
            if (previous.url != url) {
                previous.url = url
                previous.failed = null; previous.failures = 0; previous.retryAfter = 0L
            }
            // 同一个控件重复请求同一张图时保留已经显示的位图，只调整请求优先级。
            previous.priority = priority.coerceIn(PRIORITY_FOCUSED, PRIORITY_PREFETCH)
            request(previous)
            return
        }
        // 详情页切季可保留旧海报，等新海报就绪后直接替换。
        val keepPreviousImage = (keepCurrentImage || previous?.cacheUrl == cacheUrl) && view.drawable != null
        if (previous != null) unbind(previous)
        if (url.isBlank()) { view.setImageDrawable(null); return }
        val binding = Binding(view, owner, url, cacheUrl, priority.coerceIn(PRIORITY_FOCUSED, PRIORITY_PREFETCH), widthPx, heightPx)
        binding.layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> request(binding) }
        binding.attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                binding.failed = null; binding.failures = 0; binding.retryAfter = 0L
                request(binding)
            }
            // RecyclerView 的临时 detach 不代表回收；保留监听器和已显示的图片，重新 attach 时续载。
            override fun onViewDetachedFromWindow(v: View) { detachJob(binding) }
        }
        bindings[view] = binding
        view.addOnLayoutChangeListener(binding.layout)
        view.addOnAttachStateChangeListener(binding.attach)
        // 优先同步命中内存缓存；未命中且没有旧图可保留时才清空控件。
        // applied 必须与 key 一起记录，不能在 key 尚未计算时写成 null，否则后续仍会当成未显示。
        val cached = peekCached(view, cacheUrl, widthPx, heightPx)
        if (cached != null) {
            view.setImageBitmap(cached.second)
            binding.key = cached.first
            binding.applied = cached.first
        } else if (!keepPreviousImage) view.setImageDrawable(null)
        request(binding)
    }

    /** 只在卡片真正回收、换内容或页面销毁时解除绑定。 */
    fun clear(view: ImageView) {
        bindings[view]?.let(::unbind)
        view.setImageDrawable(null)
    }

    /** 进入详情或后台前保留可见封面；下一次进入时替换，内存紧张时释放。 */
    fun retainVisible() {
        val visible = Rect()
        val retained = linkedMapOf<Key, Bitmap>()
        val bitmaps = mutableSetOf<Bitmap>()
        var bytes = 0L
        bindings.values.sortedBy { it.priority }.forEach { binding ->
            val view = binding.view.get() ?: return@forEach
            val key = binding.applied ?: return@forEach
            if (!view.isShown || !view.getGlobalVisibleRect(visible)) return@forEach
            val bitmap = (view.drawable as? BitmapDrawable)?.bitmap ?: return@forEach
            if (bitmap.isRecycled) return@forEach
            val extra = if (bitmap in bitmaps) 0 else bitmap.allocationByteCount
            if (bytes + extra > cacheBytes / 2) return@forEach
            retained[key] = bitmap
            bitmaps += bitmap
            bytes += extra
        }
        returnBitmaps.clear()
        returnBitmaps.putAll(retained)
    }

    /** 无需创建离屏 ImageView，按列表实际尺寸预先下载并解码；可见请求始终优先。 */
    fun prefetch(owner: Any, viewport: View, urls: List<String>, widthPx: Int, heightPx: Int) {
        if (closed) return
        val next = if (widthPx > 0 && heightPx > 0 && viewport.isShown && viewport.getGlobalVisibleRect(Rect())) {
            val size = imageKey("", widthPx, heightPx)
            val limit = (cacheBytes / 2L / (size.width.toLong() * size.height * 4))
                .coerceIn(1L, if (lowRam) 6L else 18L).toInt()
            urls.asSequence().filter { it.isNotBlank() }.distinctBy { ArtworkCache.identity(it) }
                .take(limit)
                .associateBy { imageKey(ArtworkCache.identity(it), widthPx, heightPx) }
        } else emptyMap()
        val previous = removePrefetch(owner, next.keys)
        if (next.isEmpty()) return
        val window = PrefetchWindow(viewport, next)
        previous?.attempted?.filter { previous.urls[it] == next[it] }?.let { window.attempted.addAll(it) }
        prefetchWindows[owner] = window
        next.forEach { (key, url) ->
            if (key !in window.attempted && cachedBitmap(key) == null) enqueue(key, url, PRIORITY_PREFETCH, prefetchOwner = owner)
        }
    }

    private fun removePrefetch(owner: Any, keep: Set<Key> = emptySet()): PrefetchWindow? {
        val previous = prefetchWindows.remove(owner) ?: return null
        previous.urls.keys.filter { it !in keep }.forEach { key ->
            val cancelled = synchronized(signal) {
                jobs[key]?.let { job ->
                    job.prefetchers.remove(owner)
                    if (job.listeners.isEmpty() && job.prefetchers.isEmpty()) { cancelLocked(job); job } else null
                }
            }
            cancelled?.call?.cancel()
        }
        return previous
    }

    private fun imageKey(url: String, width: Int, height: Int): Key {
        val scale = min(1.0, 1024.0 / max(width, height))
        return Key(url, max(1, (width * scale).toInt()), max(1, (height * scale).toInt()))
    }

    /** 外层滚动时重新提升可见海报的优先级，合并同一帧内的频繁通知。 */
    fun refreshVisible() {
        if (closed || viewportRefreshPosted) return
        viewportRefreshPosted = true
        main.post(viewportRefresh)
    }

    /** 布局前先显示同封面的已有位图；布局后再按实际尺寸决定是否补充更清晰的图片。 */
    private fun peekCached(view: ImageView, url: String, widthPx: Int, heightPx: Int): Pair<Key, Bitmap>? {
        val width = if (widthPx > 0) widthPx else view.width - view.paddingLeft - view.paddingRight
        val height = if (heightPx > 0) heightPx else view.height - view.paddingTop - view.paddingBottom
        if (width > 0 && height > 0) {
            val key = imageKey(url, width, height)
            cachedBitmap(key)?.let { return key to it }
        }
        // 同封面的较小位图也可先显示；按新尺寸补解码期间不回退到空白。
        val fallback = (returnBitmaps.keys + cache.snapshot().keys).filter { it.url == url }.minByOrNull {
            if (width > 0 && height > 0) abs(it.width - width).toLong() + abs(it.height - height)
            else it.width.toLong() * it.height
        } ?: return null
        return (returnBitmaps[fallback] ?: cache.get(fallback))?.let { fallback to it }
    }

    /** 大海报可直接用于最近观看的小卡片；少量尺寸误差也不重复解码。 */
    private fun cachedBitmap(key: Key): Bitmap? {
        (returnBitmaps[key] ?: cache.get(key))?.let { return it }
        val compatible = (returnBitmaps.keys + cache.snapshot().keys).filter { candidate ->
            candidate.url == key.url && candidate.width * 10 >= key.width * 9 && candidate.height * 10 >= key.height * 9
        }.minByOrNull { abs(it.width - key.width) + abs(it.height - key.height) } ?: return null
        // 读取实际条目更新 LRU 顺序，不为同一位图建立多个别名，避免重复计入内存预算。
        return returnBitmaps[compatible] ?: cache.get(compatible)
    }

    private fun request(binding: Binding) {
        val view = binding.view.get() ?: return
        if (closed || binding.owner != owner || bindings[view] !== binding || !view.isAttachedToWindow) return
        // 保留的其他页签不抢占当前页的下载队列；切回时由 refreshVisible 续载。
        if (!view.isShown) { detachJob(binding); return }
        val width = if (binding.width > 0) binding.width else view.width - view.paddingLeft - view.paddingRight
        val height = if (binding.height > 0) binding.height else view.height - view.paddingTop - view.paddingBottom
        if (width <= 0 || height <= 0) return
        // A malformed layout cannot ask an old TV to allocate a full-screen, multi-megapixel thumbnail.
        val key = imageKey(binding.cacheUrl, width, height)
        if (binding.key != key) {
            detachJob(binding); binding.key = key; binding.failed = null
            binding.failures = 0; binding.retryAfter = 0L
        }
        if (binding.applied == key && view.drawable != null) return
        if (binding.applied == key) binding.applied = null
        if (binding.failed == key) {
            if (binding.failures >= 3 || SystemClock.uptimeMillis() < binding.retryAfter) return
            binding.failed = null
        }
        cachedBitmap(key)?.let { bitmap -> view.setImageBitmap(bitmap); binding.applied = key; return }
        // NestedScrollView 会测量并挂载大量离屏卡片，不能让它们抢占当前可见海报的队列。
        val priority = if (binding.priority == PRIORITY_VISIBLE && (!view.isShown || !view.getGlobalVisibleRect(Rect())))
            PRIORITY_PREFETCH else binding.priority
        enqueue(key, binding.url, priority, binding = binding)
    }

    private fun enqueue(key: Key, url: String, priority: Int, binding: Binding? = null, prefetchOwner: Any? = null) {
        val cancelled = mutableListOf<Job>()
        synchronized(signal) {
            // 已经开始下载的纯预取也要给新出现的可见封面让路，不能只调整排队顺序。
            if (priority < PRIORITY_PREFETCH) jobs.values.firstOrNull {
                it.key != key && it.priority == PRIORITY_PREFETCH && it.listeners.isEmpty() && it.call != null
            }?.let { job -> cancelLocked(job); cancelled += job }
            jobs[key]?.let { job ->
                binding?.let { job.listeners += it }
                prefetchOwner?.let { job.prefetchers += it }
                if (priority < job.priority) {
                    val queuedCache = cachedPending.remove(job)
                    val queuedNetwork = pending.remove(job)
                    job.priority = priority
                    if (queuedCache) cachedPending.add(job)
                    if (queuedNetwork) pending.add(job)
                }
                return@synchronized
            }
            if (cachedPending.size + pending.size >= MAX_PENDING) {
                val worst = (cachedPending.asSequence() + pending.asSequence())
                    .maxWithOrNull(compareBy<Job> { it.priority }.thenBy { -it.order })!!
                if (worst.priority < priority || (worst.priority == priority && priority == PRIORITY_PREFETCH)) return@synchronized
                cancelLocked(worst)
                cancelled += worst
            }
            val job = Job(key, url, owner, priority, ++order)
            binding?.let { job.listeners += it }
            prefetchOwner?.let { job.prefetchers += it }
            jobs[key] = job
            cachedPending += job
            signal.notifyAll()
        }
        cancelled.forEach { it.call?.cancel() }
    }

    private fun cancelLocked(job: Job) {
        job.cancelled = true
        cachedPending.remove(job)
        pending.remove(job)
        if (jobs[job.key] === job) jobs.remove(job.key)
        job.listeners.clear()
        job.prefetchers.clear()
    }

    private fun detachJob(binding: Binding) {
        val key = binding.key ?: return
        val cancelled = synchronized(signal) {
            jobs[key]?.takeIf { it.owner == binding.owner }?.let { job ->
                job.listeners.remove(binding)
                if (job.listeners.isEmpty() && job.prefetchers.isEmpty()) { cancelLocked(job); job } else null
            }
        }
        cancelled?.call?.cancel()
    }

    private fun unbind(binding: Binding) {
        binding.view.get()?.let { view ->
            view.removeOnLayoutChangeListener(binding.layout)
            view.removeOnAttachStateChangeListener(binding.attach)
            if (bindings[view] === binding) bindings.remove(view)
        }
        detachJob(binding)
        binding.view.clear()
    }

    /** Release every old-page listener and bitmap binding, including queued and in-flight requests. */
    fun cancelPage(clearMemory: Boolean = false) {
        main.removeCallbacks(viewportRefresh); viewportRefreshPosted = false
        main.removeCallbacks(retryVisible)
        owner++
        prefetchWindows.clear()
        val old = bindings.values.toList()
        old.forEach { binding -> binding.view.get()?.setImageDrawable(null); unbind(binding) }
        val cancelled = synchronized(signal) {
            jobs.values.toList().also { list -> list.forEach(::cancelLocked); signal.notifyAll() }
        }
        cancelled.forEach { it.call?.cancel() }
        if (clearMemory) { returnBitmaps.clear(); cache.evictAll() }
    }

    fun cancelAll(clearMemory: Boolean = false) = cancelPage(clearMemory)

    @Suppress("DEPRECATION") // Android 8 devices still report the running-memory pressure levels.
    fun trimMemory(level: Int) {
        when {
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                returnBitmaps.clear(); cache.evictAll()
            }
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                returnBitmaps.clear(); cache.trimToSize(cacheBytes / 2)
            }
            // UI_HIDDEN 仅表示切到后台，不代表内存紧张；保留缓存让返回时直接显示。
        }
    }

    fun close() {
        if (closed) return
        closed = true
        cancelPage()
        returnBitmaps.clear()
        synchronized(signal) { signal.notifyAll() }
        workers.shutdownNow()
        cacheWorker.shutdownNow()
        // TLS socket pool disposal can block and must not happen on the UI thread.
        Thread({
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }, "hongguotv-artwork-cleanup").start()
    }

    private fun work(localOnly: Boolean) {
        val queue = if (localOnly) cachedPending else pending
        while (!closed) {
            val job = try {
                synchronized(signal) {
                    while (queue.isEmpty() && !closed) signal.wait()
                    if (closed) null else queue.poll()
                }
            } catch (_: InterruptedException) { return } ?: return
            val bitmap = try {
                if (job.cancelled) null else cache.get(job.key) ?: if (localOnly) {
                    disk.get(job.url)?.let { decode(job, it) }
                } else fetch(job)
            } catch (_: Exception) { null } catch (_: OutOfMemoryError) { cache.evictAll(); null }
            if (localOnly && bitmap == null && !job.cancelled) {
                synchronized(signal) {
                    if (!closed && !job.cancelled && jobs[job.key] === job) {
                        pending += job
                        signal.notifyAll()
                    }
                }
                continue
            }
            val listeners = synchronized(signal) {
                if (jobs[job.key] === job) jobs.remove(job.key)
                if (!job.cancelled && !closed && bitmap != null) cache.put(job.key, bitmap)
                job.listeners.toList().also { job.listeners.clear(); job.prefetchers.clear() }
            }
            if (!job.cancelled && !closed) main.post {
                if (job.owner == owner && !closed) {
                    // 同一窗口内只预取一次，避免失败链接或位图被淘汰后在后台循环加载。
                    prefetchWindows.values.forEach { window ->
                        if (window.urls[job.key] == job.url) window.attempted += job.key
                    }
                    var retry = false
                    listeners.forEach { binding ->
                        val view = binding.view.get()
                        if (view != null && bindings[view] === binding && binding.key == job.key) {
                            if (bitmap != null) {
                                view.setImageBitmap(bitmap); binding.applied = job.key
                                binding.failed = null; binding.failures = 0
                            } else {
                                binding.failed = job.key; binding.failures++
                                binding.retryAfter = SystemClock.uptimeMillis() + 2_000L
                                if (binding.failures < 3) retry = true
                            }
                        }
                    }
                    refillVisible()
                    if (retry) {
                        main.removeCallbacks(retryVisible)
                        main.postDelayed(retryVisible, 2_000L)
                    }
                }
            }
        }
    }

    // Dropped preloads must not leave a visible card blank when a queue slot becomes free.
    private fun refillVisible() {
        val visible = Rect()
        prefetchWindows.toMap().forEach { (owner, window) ->
            val view = window.view.get()
            if (view == null || !view.isShown || !view.getGlobalVisibleRect(visible)) {
                // 隐藏页签和相邻分页让出下载、解码线程，重新展示后由列表更新预取窗口。
                removePrefetch(owner)
            }
        }
        val snapshot = bindings.values.toList()
        snapshot.filter { it.view.get()?.isShown != true }.forEach(::detachJob)
        snapshot.filter { binding ->
            val view = binding.view.get()
            view?.isShown == true && view.isAttachedToWindow && view.getGlobalVisibleRect(visible) &&
                (binding.applied != binding.key || view.drawable == null)
        }.sortedBy { it.priority }.take(MAX_PENDING).forEach(::request)
        prefetchWindows.toMap().forEach { (owner, window) ->
            window.urls.forEach { (key, url) ->
                if (key !in window.attempted && cachedBitmap(key) == null) enqueue(key, url, PRIORITY_PREFETCH, prefetchOwner = owner)
            }
        }
    }

    private fun fetch(job: Job): Bitmap? {
        val bytes = disk.getOrLoad(job.url) {
            if (job.cancelled) return@getOrLoad null
            val call = http.newCall(Request.Builder().url(job.url).build())
            job.call = call
            if (job.cancelled) { call.cancel(); return@getOrLoad null }
            call.execute().use { response ->
                val body = response.body ?: return@getOrLoad null
                if (!response.isSuccessful || body.contentLength() > MAX_BYTES) return@getOrLoad null
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                body.byteStream().use { input ->
                    while (!job.cancelled) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (out.size() + count > MAX_BYTES) return@getOrLoad null
                        out.write(buffer, 0, count)
                    }
                }
                if (job.cancelled) return@getOrLoad null
                out.toByteArray()
            }
        } ?: return null
        return decode(job, bytes)
    }

    private fun decode(job: Job, bytes: ByteArray): Bitmap? {
        if (job.cancelled) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..32768 || bounds.outHeight !in 1..32768) {
            disk.remove(job.url)
            return null
        }
        // Fit the entire source into the measured target. Cropping at decode time cannot be
        // undone by ImageView.FIT_CENTER and used to remove most of a portrait poster.
        val fit = min(1.0, min(job.key.width.toDouble() / bounds.outWidth, job.key.height.toDouble() / bounds.outHeight))
        val width = max(1, (bounds.outWidth * fit).toInt())
        val height = max(1, (bounds.outHeight * fit).toInt())
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        if ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > (if (lowRam) 2 else 4) * 1024 * 1024) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: run {
            disk.remove(job.url)
            return null
        }
        if (job.cancelled) { decoded.recycle(); return null }
        val result = try { Bitmap.createScaledBitmap(decoded, width, height, true) }
        catch (problem: Throwable) { decoded.recycle(); throw problem }
        if (result !== decoded) decoded.recycle()
        if (job.cancelled) { result.recycle(); return null }
        return result
    }
}
