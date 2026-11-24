// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.graphics.Bitmap
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.bitmap.BitmapResource
import com.huluwa.heif.HeifDecoder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/** 系统解码器失败后补充 HEIC 软件解码，复用 Glide 的位图池和磁盘缓存。 */
internal object ArtworkCompatibility {
    fun register(glide: Glide, registry: Registry) {
        registry
            .append(Registry.BUCKET_BITMAP, ByteBuffer::class.java, Bitmap::class.java,
                object : ResourceDecoder<ByteBuffer, Bitmap> {
                    override fun handles(source: ByteBuffer, options: Options) = HeifDecoder.handles(source)
                    override fun decode(source: ByteBuffer, width: Int, height: Int, options: Options): Resource<Bitmap>? {
                        val input = source.asReadOnlyBuffer()
                        if (input.remaining() > HeifDecoder.MAX_INPUT_BYTES) throw IOException("HEIC 图片文件过大")
                        val bytes = ByteArray(input.remaining()).also { input.get(it) }
                        return BitmapResource.obtain(HeifDecoder.decode(bytes, width, height), glide.bitmapPool)
                    }
                })
            .append(Registry.BUCKET_BITMAP, InputStream::class.java, Bitmap::class.java,
                object : ResourceDecoder<InputStream, Bitmap> {
                    override fun handles(source: InputStream, options: Options): Boolean {
                        if (!source.markSupported()) return false
                        val header = ByteArray(128)
                        source.mark(header.size)
                        var count = 0
                        try {
                            while (count < header.size) {
                                val read = source.read(header, count, header.size - count)
                                if (read <= 0) break
                                count += read
                            }
                        } finally {
                            source.reset()
                        }
                        return HeifDecoder.handles(ByteBuffer.wrap(header, 0, count))
                    }
                    override fun decode(source: InputStream, width: Int, height: Int, options: Options): Resource<Bitmap>? {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            if (output.size() + read > HeifDecoder.MAX_INPUT_BYTES) throw IOException("HEIC 图片文件过大")
                            output.write(buffer, 0, read)
                        }
                        return BitmapResource.obtain(HeifDecoder.decode(output.toByteArray(), width, height), glide.bitmapPool)
                    }
                })
    }
}
