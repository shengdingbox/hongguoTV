// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.ActivityManager
import android.content.Context
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.module.AppGlideModule

/** 只配置 Glide 自带的缓存和位图池，不维护应用自己的图片缓存。 */
@GlideModule
class ArtworkGlideModule : AppGlideModule() {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        val manager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lowRam = manager?.isLowRamDevice == true
        val memoryClass = manager?.memoryClass ?: 128
        val resourceBytes = (if (lowRam) 8 else (memoryClass / 6).coerceIn(16, 48)) * 1024L * 1024
        val poolBytes = (if (lowRam) 4 else (memoryClass / 12).coerceIn(8, 24)) * 1024L * 1024
        builder.setMemoryCache(LruResourceCache(resourceBytes))
            .setBitmapPool(LruBitmapPool(poolBytes))
            .setDiskCache(InternalCacheDiskCacheFactory(context, "glide-artwork", 128L * 1024 * 1024))
    }

    override fun isManifestParsingEnabled() = false
}
