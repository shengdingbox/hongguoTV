// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapResource
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * HEIC 封面兜底解码：Android 9 以前系统没有 HEIC 解码器，当贝等老盒子上 Glide 全军覆没。
 * 这里解析容器取出单帧 HEVC（hvc1），借电视芯片必带的 HEVC 硬解渲染到 ImageReader 拿 Bitmap。
 * 仅在 SDK < 28 时注册；解码失败直接抛异常，Glide 会按原链路继续尝试，不影响其他格式。
 */
object HeicHardDecoder {
    private const val TAG = "HEIC"
    private val codecPermits = Semaphore(2)
    private val readerThread by lazy { HandlerThread("heic-reader").apply { start() } }
    private val readerHandler by lazy { Handler(readerThread.looper) }

    /** 并发上限 2：首页几十张封面排队解，别把盒子上留给视频播放的 codec 实例全占掉。 */
    fun decode(image: HeicImageParser.HeicImage, targetWidth: Int, targetHeight: Int): Bitmap {
        val started = android.os.SystemClock.elapsedRealtime()
        codecPermits.acquire()
        var codec: MediaCodec? = null
        var reader: ImageReader? = null
        try {
            reader = ImageReader.newInstance(image.width, image.height, PixelFormat.RGBA_8888, 2)
            val rendered = CountDownLatch(1)
            reader.setOnImageAvailableListener({ rendered.countDown() }, readerHandler)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, image.width, image.height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(image.hvcC))
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            codec.configure(format, reader.surface, null, 0)
            codec.start()
            val inputIndex = codec.dequeueInputBuffer(1_000)
            if (inputIndex < 0) throw IOException("HEIC 等待输入缓冲超时")
            val input = codec.getInputBuffer(inputIndex) ?: throw IOException("HEIC 输入缓冲缺失")
            if (input.remaining() < image.frame.size) throw IOException("HEIC 输入缓冲不足")
            input.put(image.frame)
            codec.queueInputBuffer(inputIndex, 0, image.frame.size, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            if (!rendered.await(4_000, TimeUnit.MILLISECONDS)) throw IOException("HEIC 硬解输出超时")
            val captured = reader.acquireLatestImage() ?: throw IOException("HEIC 硬解无帧")
            captured.use { raw ->
                val bitmap = rawToBitmap(raw)
                val result = scaleInto(bitmap, targetWidth, targetHeight)
                RuntimeLog.add("$TAG OK ${image.width}x${image.height}->${result.width}x${result.height} ${android.os.SystemClock.elapsedRealtime() - started}ms")
                return result
            }
        } catch (e: Exception) {
            RuntimeLog.add("$TAG fail ${e.javaClass.simpleName}: ${e.message?.take(60)} ${android.os.SystemClock.elapsedRealtime() - started}ms")
            throw e
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { reader?.close() }
            codecPermits.release()
        }
    }

    /** ImageReader 的 RGBA 平面可能带行对齐 padding，先读进等宽 bitmap 再裁掉。 */
    private fun rawToBitmap(raw: android.media.Image): Bitmap {
        val plane = raw.planes[0]
        val buffer = plane.buffer.duplicate()
        buffer.rewind()
        val width = raw.width
        val height = raw.height
        val rowPadding = (plane.rowStride - width * plane.pixelStride) / plane.pixelStride
        val full = Bitmap.createBitmap(width + rowPadding, height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(buffer)
        if (rowPadding == 0) return full
        val tight = Bitmap.createBitmap(full, 0, 0, width, height)
        if (tight !== full) full.recycle()
        return tight
    }

    /** 目标尺寸有效时按 CENTER_INSIDE 语义等比缩小，省内存；放大无意义直接返回原图。 */
    private fun scaleInto(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (targetWidth <= 0 || targetHeight <= 0) return source
        val scale = min(targetWidth.toFloat() / source.width, targetHeight.toFloat() / source.height)
        if (scale >= 1f) return source
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (scaled !== source) source.recycle()
        return scaled
    }
}

/** 磁盘缓存路径的 HEIC 数据解码入口。 */
class HeicByteBufferDecoder(private val pool: BitmapPool) : ResourceDecoder<ByteBuffer, Bitmap> {
    override fun handles(source: ByteBuffer, options: Options): Boolean {
        val copy = source.duplicate()
        if (copy.remaining() < 12) return false
        val head = ByteArray(32)
        copy.get(head, 0, min(copy.remaining(), 32))
        return HeicImageParser.isHeic(head, min(32, head.size))
    }

    override fun decode(source: ByteBuffer, width: Int, height: Int, options: Options): Resource<Bitmap> {
        val bytes = ByteArray(source.remaining())
        source.get(bytes)
        return decodeBytes(bytes, width, height, pool)
    }
}

/** 网络路径的 HEIC 数据解码入口。不可 mark 的流乐观放行，由 parse 阶段做最终判定。 */
class HeicStreamDecoder(private val pool: BitmapPool) : ResourceDecoder<InputStream, Bitmap> {
    override fun handles(source: InputStream, options: Options): Boolean {
        if (!source.markSupported()) return true
        source.mark(32)
        val head = ByteArray(32)
        var read = 0
        while (read < head.size) {
            val n = source.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        source.reset()
        return read >= 12 && HeicImageParser.isHeic(head, read)
    }

    override fun decode(source: InputStream, width: Int, height: Int, options: Options): Resource<Bitmap> =
        decodeBytes(source.readBytes(), width, height, pool)
}

private fun decodeBytes(bytes: ByteArray, width: Int, height: Int, pool: BitmapPool): Resource<Bitmap> {
    if (!HeicImageParser.isHeic(bytes)) throw IOException("HEIC 兜底解码器收到了非 HEIC 数据")
    val image = HeicImageParser.parse(bytes)
    val bitmap = HeicHardDecoder.decode(image, width, height)
    return BitmapResource.obtain(bitmap, pool)
}
