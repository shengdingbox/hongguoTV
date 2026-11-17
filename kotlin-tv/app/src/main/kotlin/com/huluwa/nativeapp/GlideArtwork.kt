// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.Request
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/** 页面只记录自己绑定的控件；图片请求、缓存、解码和资源回收全部交给 Glide。 */
class GlideArtwork(private val context: Context) {
    private val requests = Glide.with(context)
    private val identities = HashMap<String, String>()
    private val bound = WeakHashMap<ImageView, String>()
    private var closed = false

    fun identity(url: String): String = identities.getOrPut(url) { ArtworkUrl.identity(url) }

    fun load(view: ImageView, url: String, priority: Priority = Priority.NORMAL, keepCurrentImage: Boolean = false) {
        if (closed || keepCurrentImage && url.isBlank()) return
        if (url.isBlank()) { clear(view); return }
        val identity = identity(url)
        val previous = bound[view]
        val showing = view.drawable
        val short = identity.takeLast(20)
        val viewId = Integer.toHexString(System.identityHashCode(view))
        if (previous == identity && (keepCurrentImage || showing != null) && isSameRequestComplete(view)) {
            RuntimeLog.add("IMG skip v=$viewId id=…$short showing=${showing != null}")
            return
        }
        if (previous == identity && showing != null) {
            // 同一封面仍在加载或上次失败：直接重试，绝不让 Glide 清空当前显示。
            // 焦点抖动、DiffUtil 局部刷新、签名轮换后重绑都会命中这里。
            RuntimeLog.add("IMG retry v=$viewId id=…$short (上次未完成,保旧图)")
            val retry = coverRequest(requests, url, priority)
            retry.placeholder(showing)
            retry.listener(traceListener(viewId, short, "retry"))
            retry.into(view)
            return
        }
        bound[view] = identity
        RuntimeLog.add("IMG into v=$viewId id=…$short prev=${previous?.takeLast(8) ?: "null"} showing=${showing != null} keep=$keepCurrentImage")
        val request = coverRequest(requests, url, priority)
        // 任何已有显示都用 placeholder 保住：共享动画快照、上一张封面、旧图一律在新图 ready 前显示。
        // 首屏 showing=null 时不设 placeholder，露出卡片背景（与 Glide 迁移前的旧行为一致）。
        // 不再用 .thumbnail() 加载旧图，那会重新触发两阶段切换（缩略图→原图），首屏就是闪烁本身。
        if (showing != null) request.placeholder(showing)
        request.listener(traceListener(viewId, short, "into"))
        request.into(view)
    }

    /** 监听每次 into/retry 真正的资源到位/失败，定位"旧图被替换/清空"的瞬间。 */
    private fun traceListener(viewId: String, shortId: String, tag: String): RequestListener<Bitmap> {
        val seq = traceSeq.incrementAndGet()
        RuntimeLog.add("IMG #$seq $tag-req v=$viewId id=…$shortId")
        return object : RequestListener<Bitmap> {
            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>, isFirstResource: Boolean): Boolean {
                RuntimeLog.add("IMG #$seq $tag-FAIL v=$viewId id=…$shortId err=${e?.javaClass?.simpleName}")
                return false
            }
            override fun onResourceReady(resource: Bitmap, model: Any, target: Target<Bitmap>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                RuntimeLog.add("IMG #$seq $tag-OK v=$viewId id=…$shortId src=$dataSource ${resource.width}x${resource.height}")
                return false
            }
        }
    }

    /** ViewTarget 在指定 tag 中直接保存 Request，不是 Target；读取错误会让同图跳过重载永远失效。 */
    private fun isSameRequestComplete(view: ImageView): Boolean {
        val tag = runCatching { view.getTag(com.bumptech.glide.R.id.glide_custom_view_target_tag) }.getOrNull()
        return (tag as? Request)?.isComplete == true
    }

    fun clear(view: ImageView) {
        val viewId = Integer.toHexString(System.identityHashCode(view))
        RuntimeLog.add("IMG clear v=$viewId had=${view.drawable != null}")
        bound.remove(view)
        requests.clear(view)
        view.setImageDrawable(null)
    }

    fun clearPage(clearMemory: Boolean = false) {
        val n = bound.size
        RuntimeLog.add("IMG clearPage n=$n clearMem=$clearMemory caller=${Throwable().stackTrace.getOrNull(1)?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }}")
        bound.keys.toList().forEach(::clear)
        if (clearMemory) Glide.get(context).clearMemory()
    }

    fun close() { closed = true; clearPage() }

    /** 不绑定视图，仅把封面拉进 Glide 磁盘/内存缓存。
     *  排行榜、新剧等网格首屏数据返回后调用，让 RecyclerView 真正的 onBind 命中缓存秒开。 */
    fun preload(urls: List<String>, width: Int = 0, height: Int = 0) {
        if (closed) return
        urls.filter { it.isNotBlank() }.forEach { url ->
            val req = coverRequest(requests, url, Priority.HIGH)
            if (width > 0 && height > 0) req.override(width, height)
            req.preload()
        }
        RuntimeLog.add("IMG preload n=${urls.size}")
    }

    companion object {
        /** 请求序列号：追踪一次 into/retry 到最终 OK/FAIL 的完整链路。 */
        private val traceSeq = AtomicLong(0)
        /** 展示和 RecyclerViewPreloader 共用选项，尺寸由实际 ImageView 提供，缓存键保持一致。 */
        fun coverRequest(requests: RequestManager, url: String, priority: Priority = Priority.NORMAL): RequestBuilder<Bitmap> =
            requests.asBitmap().load(ArtworkUrl(url))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .downsample(DownsampleStrategy.CENTER_INSIDE)
                .dontTransform()
                .dontAnimate()
                .disallowHardwareConfig()
                .timeout(10_000)
                // 不再用 .thumbnail(.25f)：日志证实它会让同一 request 触发两次 onResourceReady，
                // 第一次 25% 缩略图（如 101x144）瞬间硬切到原图（400x572），首屏就是「白屏→小图→大图」的闪烁。
                .priority(priority)
    }
}
