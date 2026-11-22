// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.data.InputStreamRewinder
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.model.ByteBufferEncoder
import com.bumptech.glide.load.model.ByteBufferFileLoader
import com.bumptech.glide.load.model.FileLoader
import com.bumptech.glide.load.model.StreamEncoder
import com.bumptech.glide.load.model.StringLoader
import com.bumptech.glide.load.model.UrlUriLoader
import com.bumptech.glide.load.resource.bitmap.BitmapEncoder
import com.bumptech.glide.load.resource.bitmap.ByteBufferBitmapDecoder
import com.bumptech.glide.load.resource.bitmap.DefaultImageHeaderParser
import com.bumptech.glide.load.resource.bitmap.Downsampler
import com.bumptech.glide.load.resource.bitmap.ExifInterfaceImageHeaderParser
import com.bumptech.glide.load.resource.bitmap.StreamBitmapDecoder
import com.bumptech.glide.load.resource.bytes.ByteBufferRewinder
import com.bumptech.glide.load.resource.transcode.BitmapDrawableTranscoder
import com.bumptech.glide.module.AppGlideModule
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer

/** 使用 Glide 自带的缓存和位图池，只注册静态网络封面及磁盘缓存需要的解码链。 */
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

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        // 发布包由 proguard-rules.pro 移除 Glide 4.16.0 的默认注册调用，
        // 在这里补齐网络封面、原图缓存和解码后缓存的全部依赖，供 R8 裁掉 GIF 和视频缩略图组件。
        // OkHttpLibraryGlideModule 仍由注解生成模块注册 GlideUrl -> InputStream。
        val bitmapPool = glide.bitmapPool
        val arrayPool = glide.arrayPool
        val resources = context.resources
        registry.register(DefaultImageHeaderParser())
        if (Build.VERSION.SDK_INT >= 27) registry.register(ExifInterfaceImageHeaderParser())
        val downsampler = Downsampler(registry.imageHeaderParsers, resources.displayMetrics, bitmapPool, arrayPool)

        // Android 9 以前系统无 HEIC 解码器（当贝等老盒子封面全灭），
        // HEIC 数据前置交给芯片 HEVC 硬解兜底；其他格式仍走后面的标准链路。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            registry.prepend(Registry.BUCKET_BITMAP, ByteBuffer::class.java, Bitmap::class.java, HeicByteBufferDecoder(glide.bitmapPool))
            registry.prepend(Registry.BUCKET_BITMAP, InputStream::class.java, Bitmap::class.java, HeicStreamDecoder(glide.bitmapPool))
        }
        registry
            .append(ByteBuffer::class.java, ByteBufferEncoder())
            .append(InputStream::class.java, StreamEncoder(arrayPool))
            .append(Registry.BUCKET_BITMAP, ByteBuffer::class.java, Bitmap::class.java, ByteBufferBitmapDecoder(downsampler))
            .append(Registry.BUCKET_BITMAP, InputStream::class.java, Bitmap::class.java, StreamBitmapDecoder(downsampler, arrayPool))
            .append(Bitmap::class.java, BitmapEncoder(arrayPool))
            .register(ByteBufferRewinder.Factory())
            .register(InputStreamRewinder.Factory(arrayPool))
            .append(File::class.java, ByteBuffer::class.java, ByteBufferFileLoader.Factory())
            .append(File::class.java, InputStream::class.java, FileLoader.StreamFactory())
            .append(String::class.java, InputStream::class.java, StringLoader.StreamFactory())
            .append(Uri::class.java, InputStream::class.java, UrlUriLoader.StreamFactory())
            .register(Bitmap::class.java, BitmapDrawable::class.java, BitmapDrawableTranscoder(resources))
    }

    override fun isManifestParsingEnabled() = false
}
