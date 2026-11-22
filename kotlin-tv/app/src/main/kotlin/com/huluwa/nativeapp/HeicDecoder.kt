// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapResource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    /** Amlogic ByteBuffer 模式色度走独立 DMA 段，app 读到全零（图变绿），只能退系统软解。 */
    @Volatile private var hardwareChromaBroken = false

    private const val SOFT_DECODER = "OMX.google.hevc.decoder"
    @Volatile private var softDecoderBroken = false
    private val readerThread by lazy { HandlerThread("heic-reader").apply { start() } }
    private val readerHandler by lazy { Handler(readerThread.looper) }

    /** 并发上限 2：首页几十张封面排队解，别把盒子上留给视频播放的 codec 实例全占掉。 */
    fun decode(image: HeicImageParser.HeicImage, targetWidth: Int, targetHeight: Int): Bitmap {
        val started = SystemClock.elapsedRealtime()
        codecPermits.acquire()
        try {
            // 四级降级（当贝 Amlogic 实测：RGBA ImageReader 不支持、色度走独立 DMA、软解组件被 ROM 裁掉）：
            // RGBA Surface 直出 → 硬件 ByteBuffer(色度检测) → 系统软解 → GL SurfaceTexture 渲染读回。
            val bitmap = attempt("surface", surfaceModeBroken, { surfaceModeBroken = true }) { decodeToSurface(image) }
                ?: attempt("hw-buf", hardwareChromaBroken, { hardwareChromaBroken = true }) { decodeToBuffers(image, soft = false) }
                ?: attempt("soft", softDecoderBroken, { softDecoderBroken = true }) { decodeToBuffers(image, soft = true) }
                ?: glDecode(image)
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

    /** 单级降级封装：已判死的路径直接跳过，失败的路径打日志并标记，返回 null 走下一级。 */
    private inline fun attempt(tag: String, broken: Boolean, markBroken: () -> Unit, block: () -> Bitmap): Bitmap? {
        if (broken) return null
        return try {
            block()
        } catch (e: Exception) {
            markBroken()
            RuntimeLog.add("$TAG $tag 路径失败(${e.javaClass.simpleName}: ${e.message?.take(50)})，降级")
            null
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

    /** ByteBuffer 兜底：拿 YUV_420_888 输出经 NV21→JPEG 转成 Bitmap；soft=true 用系统软解码器。 */
    private fun decodeToBuffers(image: HeicImageParser.HeicImage, soft: Boolean): Bitmap {
        var codec: MediaCodec? = null
        try {
            codec = if (soft) {
                if (softDecoderBroken) throw IOException("软解组件不可用")
                try {
                    MediaCodec.createByCodecName(SOFT_DECODER)
                } catch (e: Exception) {
                    softDecoderBroken = true
                    throw e
                }
            } else {
                MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            }
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
                                if (!soft && chromaIsBlank(yuv)) throw IOException("硬解色度输出为空")
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

    /** Amlogic 色度走独立 DMA 段时 app 读到全零（图变绿）；抽头/中/尾三段探测，全零即判硬解输出无效。 */
    private fun chromaIsBlank(yuv: Image): Boolean {
        val buf = yuv.planes[1].buffer.duplicate().rewind() as ByteBuffer
        val size = buf.remaining()
        if (size < 32) return true
        val probes = intArrayOf(0, size / 2 - 16, size - 32)
        for (probe in probes) {
            for (i in 0 until 32) {
                val idx = probe + i
                if (idx in 0 until size && buf.get(idx).toInt() != 0) return false
            }
        }
        return true
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

    private val glLock = Any()

    /** 终极兜底：解码器渲染到 SurfaceTexture（电视盒子的视频原生通路），GL 采样读回像素。
     *  不经过 CPU 可见内存，绕开 Amlogic「色度走独立 DMA 段」的限制。 */
    private fun glDecode(image: HeicImageParser.HeicImage): Bitmap {
        synchronized(glLock) {
            val gl = GlContext.obtain()
            gl.makeCurrent()
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            val texture = SurfaceTexture(tex[0])
            texture.setDefaultBufferSize(image.width, image.height)
            val rendered = CountDownLatch(1)
            texture.setOnFrameAvailableListener({ rendered.countDown() }, readerHandler)
            val surface = Surface(texture)
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                codec.configure(inputFormat(image), surface, null, 0)
                codec.start()
                queueFrame(codec, image.frame)
                if (!rendered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw IOException("HEIC GL 渲染超时")
                texture.updateTexImage()
                return gl.readTexture(tex[0], texture, image.width, image.height)
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { surface.release() }
                runCatching { texture.release() }
                GLES20.glDeleteTextures(1, tex, 0)
            }
        }
    }

    /** 进程级复用的 EGL 上下文（window surface 绑占位 SurfaceTexture，老 GPU 无 pbuffer 配置也能建）。 */
    private class GlContext private constructor() {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val surface: EGLSurface
        private val dummyTexture: SurfaceTexture = SurfaceTexture(0)
        private val dummySurface: Surface = Surface(dummyTexture)

        init {
            if (!EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) throw IOException("EGL 初始化失败")
            // 不指定 SURFACE_TYPE：pbuffer 在 Mali-450 等老 GPU 上常缺失，占位 window 用 WINDOW_BIT。
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, IntArray(1), 0) || configs[0] == null) throw IOException("EGL 配置缺失")
            surface = EGL14.eglCreateWindowSurface(display, configs[0], dummySurface, intArrayOf(EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) throw IOException("EGL window surface 创建失败")
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            if (context == EGL14.EGL_NO_CONTEXT) throw IOException("EGL 上下文创建失败")
        }

        fun makeCurrent() {
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw IOException("EGL 绑定失败")
        }

        /** 外部 OES 纹理 → FBO → glReadPixels → Bitmap，用 SurfaceTexture 变换矩阵摆正朝向。 */
        fun readTexture(externalTex: Int, source: SurfaceTexture, width: Int, height: Int): Bitmap {
            val matrix = FloatArray(16)
            source.getTransformMatrix(matrix)
            val vertex = intArrayOf(-1, -1, 1, -1, -1, 1, 1, 1)
            val fbo = IntArray(1)
            val color = IntArray(1)
            GLES20.glGenFramebuffers(1, fbo, 0)
            GLES20.glGenTextures(1, color, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, color[0])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, color[0], 0)
            val program = buildProgram()
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTex)
            val pos = GLES20.glGetAttribLocation(program, "pos")
            val mat = GLES20.glGetUniformLocation(program, "mat")
            GLES20.glUniformMatrix4fv(mat, 1, false, matrix, 0)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).apply {
                vertex.forEach { putFloat(it.toFloat()) }; rewind() })
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            GLES20.glDeleteProgram(program)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, fbo, 0)
            GLES20.glDeleteTextures(1, color, 0)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            pixels.rewind()
            bitmap.copyPixelsFromBuffer(pixels)
            return bitmap
        }

        private fun buildProgram(): Int {
            val vertexShader = "attribute vec2 pos; uniform mat4 mat; varying vec2 vUv; void main(){ gl_Position = vec4(pos, 0.0, 1.0); vUv = (mat * vec4(pos, 0.0, 1.0)).xy; }"
            val fragmentShader = "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float; uniform samplerExternalOES tex; varying vec2 vUv; void main(){ gl_FragColor = texture2D(tex, vUv); }"
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertexShader)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return program
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }

        companion object {
            @Volatile private var instance: GlContext? = null
            fun obtain(): GlContext = instance ?: synchronized(this) {
                instance ?: GlContext().also { instance = it }
            }
        }
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
