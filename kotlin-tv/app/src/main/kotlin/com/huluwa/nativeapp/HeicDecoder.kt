// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapResource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * HEIC 封面兜底解码：Android 9 以前系统没有 HEIC 解码器，当贝等老盒子上 Glide 全军覆没。
 * 这里解析容器取出单帧 HEVC（hvc1），借电视芯片必带的 HEVC 硬解得到 Bitmap。
 * 仅在 SDK < 28 时注册；解码失败直接抛异常，Glide 会按原链路继续尝试，不影响其他格式。
 */
object HeicHardDecoder {
    private const val TAG = "HEIC"
    private const val TIMEOUT_MS = 4_000L
    private val codecPermits = Semaphore(2)

    /** 某些老 OMX 组件（Amlogic）不支持渲染到 RGBA ImageReader，start failed 一次后就直走 ByteBuffer。 */
    @Volatile private var surfaceModeBroken = false
    private val readerThread by lazy { HandlerThread("heic-reader").apply { start() } }
    private val readerHandler by lazy { Handler(readerThread.looper) }

    /** 并发上限 2：首页几十张封面排队解，别把盒子上留给视频播放的 codec 实例全占掉。 */
    fun decode(image: HeicImageParser.HeicImage, targetWidth: Int, targetHeight: Int): Bitmap {
        val started = SystemClock.elapsedRealtime()
        codecPermits.acquire()
        try {
            // 首选 RGBA Surface 直出；老 OMX 组件可能不支持渲染到 ImageReader，
            // 失败后退到 ByteBuffer 模式拿 YUV 再软件转 RGB。
            val bitmap = if (surfaceModeBroken) {
                decodeToBuffers(image)
            } else {
                try {
                    decodeToSurface(image)
                } catch (e: Exception) {
                    surfaceModeBroken = true
                    RuntimeLog.add("$TAG surface 模式失败(${e.javaClass.simpleName}: ${e.message?.take(50)})，改走 ByteBuffer")
                    decodeToBuffers(image)
                }
            }
            val result = scaleInto(bitmap, targetWidth, targetHeight)
            RuntimeLog.add("$TAG OK ${image.width}x${image.height}->${result.width}x${result.height} ${SystemClock.elapsedRealtime() - started}ms")
            return result
        } catch (e: Exception) {
            RuntimeLog.add("$TAG fail ${e.javaClass.simpleName}: ${e.message?.take(60)} ${SystemClock.elapsedRealtime() - started}ms")
            throw e
        } finally {
            codecPermits.release()
        }
    }

    private fun inputFormat(image: HeicImageParser.HeicImage): MediaFormat {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, image.width, image.height)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(image.hvcC))
        return format
    }

    private fun queueFrame(codec: MediaCodec, frame: ByteArray) {
        // 超时单位是微秒：3 秒，别被毫秒坑第二次。
        val index = codec.dequeueInputBuffer(3_000_000L)
        if (index < 0) throw IOException("HEIC 等待输入缓冲超时")
        val input = codec.getInputBuffer(index) ?: throw IOException("HEIC 输入缓冲缺失")
        if (input.remaining() < frame.size) throw IOException("HEIC 输入缓冲不足")
        input.put(frame)
        codec.queueInputBuffer(index, 0, frame.size, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    /** RGBA Surface 直出：硬解直接渲染到 ImageReader，无逐像素转换，性能最好。 */
    private fun decodeToSurface(image: HeicImageParser.HeicImage): Bitmap {
        var codec: MediaCodec? = null
        var reader: ImageReader? = null
        try {
            reader = ImageReader.newInstance(image.width, image.height, PixelFormat.RGBA_8888, 2)
            val rendered = CountDownLatch(1)
            reader.setOnImageAvailableListener({ rendered.countDown() }, readerHandler)
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            codec.configure(inputFormat(image), reader.surface, null, 0)
            codec.start()
            queueFrame(codec, image.frame)
            if (!rendered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw IOException("HEIC 硬解输出超时")
            val captured = reader.acquireLatestImage() ?: throw IOException("HEIC 硬解无帧")
            captured.use { raw -> return rawToBitmap(raw) }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { reader?.close() }
        }
    }

    /** ByteBuffer 兜底：拿 YUV_420_888 输出经 NV21→JPEG 转成 Bitmap，慢但兼容性最好。 */
    private fun decodeToBuffers(image: HeicImageParser.HeicImage): Bitmap {
        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            codec.configure(inputFormat(image), null, null, 0)
            codec.start()
            queueFrame(codec, image.frame)
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                when (val index = codec.dequeueOutputBuffer(info, 50_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue
                    else -> {
                        if (index < 0) continue
                        try {
                            val out = codec.getOutputImage(index)
                                ?: throw IOException("HEIC ByteBuffer 模式无 Image 输出")
                            out.use { yuv ->
                                // TODO 临时诊断：老盒子 vendor YUV 布局与标准 YUV_420_888 不一致导致偏色，打全平面信息定位
                                if (diagnoseDumps-- > 0) {
                                    val fmt = codec.outputFormat
                                    RuntimeLog.add("$TAG plane0 s=${yuv.planes[0].rowStride}/${yuv.planes[0].pixelStride} cap=${yuv.planes[0].buffer.remaining()} p1=${yuv.planes[1].rowStride}/${yuv.planes[1].pixelStride} cap=${yuv.planes[1].buffer.remaining()} p2=${yuv.planes[2].rowStride}/${yuv.planes[2].pixelStride} cap=${yuv.planes[2].buffer.remaining()} fmt=${fmt.getInteger(MediaFormat.KEY_COLOR_FORMAT)} ${yuv.width}x${yuv.height} buf=${info.size}@${info.offset}")
                                    RuntimeLog.add("$TAG uHead=${hex8(yuv.planes[1])} vHead=${hex8(yuv.planes[2])}")
                                }
                                return yuvToBitmap(yuv)
                            }
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
            throw IOException("HEIC ByteBuffer 模式输出超时")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    private var diagnoseDumps = 3

    private fun hex8(plane: android.media.Image.Plane): String {
        val b = plane.buffer.duplicate().rewind() as ByteBuffer
        val n = min(b.remaining(), 8)
        val bytes = ByteArray(n)
        b.get(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** ImageReader 的 RGBA 平面可能带行对齐 padding，先读进等宽 bitmap 再裁掉。 */
    private fun rawToBitmap(raw: Image): Bitmap {
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

    /** YUV_420_888 → NV21 → JPEG → Bitmap。四百像素宽的小图整链 <50ms，够用。 */
    private fun yuvToBitmap(yuv: Image): Bitmap {
        val width = yuv.width
        val height = yuv.height
        val nv21 = toNv21(yuv, width, height)
        val jpeg = ByteArrayOutputStream()
        YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), 92, jpeg)
        return BitmapFactory.decodeByteArray(jpeg.toByteArray(), 0, jpeg.size())
            ?: throw IOException("HEIC JPEG 回转失败")
    }

    private fun toNv21(image: Image, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val nv21 = ByteArray(ySize + ySize / 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer.duplicate().rewind() as ByteBuffer
        for (row in 0 until height) {
            yBuf.position(row * yPlane.rowStride)
            yBuf.get(nv21, row * width, width)
        }
        val chromaHeight = height / 2
        val chromaRow = width / 2
        var pos = ySize
        if (uPlane.pixelStride == 2 && vPlane.pixelStride == 2) {
            // 半平面交错（NV12 类）：U/V 在同一底层数组相邻，按绝对位置交错读。
            val uBuf = uPlane.buffer.duplicate().rewind() as ByteBuffer
            val vBuf = vPlane.buffer.duplicate().rewind() as ByteBuffer
            for (row in 0 until chromaHeight) {
                for (col in 0 until chromaRow) {
                    nv21[pos++] = vBuf.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                    nv21[pos++] = uBuf.get(row * uPlane.rowStride + col * uPlane.pixelStride)
                }
            }
        } else {
            // planar 布局（I420 类）：先各自读出再交叉写 NV21。
            val u = ByteArray(chromaHeight * chromaRow)
            val v = ByteArray(chromaHeight * chromaRow)
            val uBuf = uPlane.buffer.duplicate().rewind() as ByteBuffer
            val vBuf = vPlane.buffer.duplicate().rewind() as ByteBuffer
            for (row in 0 until chromaHeight) {
                for (col in 0 until chromaRow) {
                    u[row * chromaRow + col] = uBuf.get(row * uPlane.rowStride + col * uPlane.pixelStride)
                    v[row * chromaRow + col] = vBuf.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                }
            }
            for (i in u.indices) {
                nv21[pos++] = v[i]
                nv21[pos++] = u[i]
            }
        }
        return nv21
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
    return BitmapResource.obtain(bitmap, pool) ?: BitmapResource(bitmap, pool)
}
