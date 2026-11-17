// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.Request
import java.util.WeakHashMap

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
        if (previous == identity && (keepCurrentImage || showing != null) && isSameRequestComplete(view)) {
            // 文案变化或 CDN 签名轮换都不重新绑定已显示的相同封面。
            // 加载中或失败的请求不跳过，前者继续加载，后者仍可按原路径重试。
            return
        }
        if (previous == identity && showing != null) {
            // 同一封面仍在加载或上次失败：直接重试，绝不让 Glide 清空当前显示。
            // 焦点抖动、DiffUtil 局部刷新、签名轮换后重绑都会命中这里。
            val retry = coverRequest(requests, url, priority)
            retry.placeholder(showing)
            retry.into(view)
            return
        }
        bound[view] = identity
        val request = coverRequest(requests, url, priority)
        if (keepCurrentImage && previous != null && previous != identity) {
            // 切季时用 Glide 自己持有上一张资源，避免把位图池中的图片当成普通 Drawable 保存。
            val retained = coverRequest(requests, previous, priority).onlyRetrieveFromCache(true)
            val width = view.width - view.paddingLeft - view.paddingRight
            val height = view.height - view.paddingTop - view.paddingBottom
            if (width > 0 && height > 0) retained.override(width, height)
            request.thumbnail(retained)
        } else if (showing != null) {
            // 任何已有显示都要保住：共享动画快照、上一张封面、placeholder 一律作为新图的就位前占位，
            // 防止 into(view) 默认 clear 造成的瞬时白屏。
            request.placeholder(showing)
        }
        request.into(view)
    }

    /** ViewTarget 在指定 tag 中直接保存 Request，不是 Target；读取错误会让同图跳过重载永远失效。 */
    private fun isSameRequestComplete(view: ImageView): Boolean {
        val tag = runCatching { view.getTag(com.bumptech.glide.R.id.glide_custom_view_target_tag) }.getOrNull()
        return (tag as? Request)?.isComplete == true
    }

    fun clear(view: ImageView) {
        bound.remove(view)
        requests.clear(view)
        view.setImageDrawable(null)
    }

    fun clearPage(clearMemory: Boolean = false) {
        bound.keys.toList().forEach(::clear)
        if (clearMemory) Glide.get(context).clearMemory()
    }

    fun close() { closed = true; clearPage() }

    companion object {
        /** 展示和 RecyclerViewPreloader 共用选项，尺寸由实际 ImageView 提供，缓存键保持一致。 */
        fun coverRequest(requests: RequestManager, url: String, priority: Priority = Priority.NORMAL): RequestBuilder<Bitmap> =
            requests.asBitmap().load(ArtworkUrl(url))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .downsample(DownsampleStrategy.CENTER_INSIDE)
                .dontTransform()
                .dontAnimate()
                .disallowHardwareConfig()
                .timeout(10_000)
                .thumbnail(.25f)
                .priority(priority)
    }
}
