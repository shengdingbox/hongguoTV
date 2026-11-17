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
import java.util.WeakHashMap

/** 页面只记录自己绑定的控件；图片请求、缓存、解码和资源回收全部交给 Glide。 */
class GlideArtwork(private val context: Context) {
    private val requests = Glide.with(context)
    private val bound = WeakHashMap<ImageView, String>()
    private var closed = false

    fun load(view: ImageView, url: String, priority: Priority = Priority.NORMAL, keepCurrentImage: Boolean = false) {
        if (closed || keepCurrentImage && url.isBlank()) return
        if (url.isBlank()) { clear(view); return }
        val previous = bound.put(view, url)
        val request = coverRequest(requests, url, priority)
        if (keepCurrentImage && previous != null && previous != url) {
            // 切季时用 Glide 自己持有上一张资源，避免把位图池中的图片当成普通 Drawable 保存。
            val retained = coverRequest(requests, previous, priority).onlyRetrieveFromCache(true)
            val width = view.width - view.paddingLeft - view.paddingRight
            val height = view.height - view.paddingTop - view.paddingBottom
            if (width > 0 && height > 0) retained.override(width, height)
            request.thumbnail(retained)
        } else if (keepCurrentImage && previous == null && view.drawable != null) {
            // 共享动画提供的是独立快照，可以作为 Glide 请求的占位图。
            request.placeholder(view.drawable)
        }
        request.into(view)
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
            requests.asBitmap().load(url)
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
