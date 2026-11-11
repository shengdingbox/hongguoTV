// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import kotlin.math.ceil

/** 手机竖屏导航的玻璃底板，只采样导航后方内容，图标和文字保持原始清晰度。 */
class PortraitGlassNavigation(context: Context, private val content: View) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val sampleSize = 4f * density
    private val blurPadding = 24f * density
    private val sourceLocation = IntArray(2)
    private val navLocation = IntArray(2)
    private val glassBounds = RectF()
    private val innerRimBounds = RectF()
    private val sheenBounds = RectF()
    private val backdropBounds = RectF()
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.2f) })
    }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private var captured: Bitmap? = null
    private var blurred: Bitmap? = null
    private var captureCanvas: Canvas? = null
    private var pixels = IntArray(0)
    private var previousPixels = IntArray(0)
    private var scratch = IntArray(0)
    private var observer: ViewTreeObserver? = null
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (isShown && width > 0 && height > 0) updateBackdrop()
        true
    }

    init {
        orientation = HORIZONTAL
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                outline.alpha = .45f
            }
        }
        clipToOutline = true
        elevation = 12f * density
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observer = viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
    }

    override fun onDetachedFromWindow() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        observer = null
        captured = null
        blurred = null
        captureCanvas = null
        pixels = IntArray(0)
        previousPixels = IntArray(0)
        scratch = IntArray(0)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glassBounds.set(density / 2f, density / 2f, w - density / 2f, h - density / 2f)
        innerRimBounds.set(glassBounds)
        innerRimBounds.inset(density, density)
        sheenBounds.set(-w * .15f, -h * .9f, w * 1.05f, h * .65f)
        // 轻微放大后方色块，配合弧面高光形成玻璃透镜的观感。
        backdropBounds.set(-blurPadding - w * .015f, -blurPadding - h * .025f,
            w + blurPadding + w * .015f, h + blurPadding + h * .025f)
        // 白底上也保留中下部的冷色折射，避免白色遮罩与页面融为一体。
        tintPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.argb(90, 255, 255, 255), Color.argb(54, 236, 243, 247),
                Color.argb(78, 180, 198, 212), Color.argb(94, 216, 228, 237)),
            floatArrayOf(0f, .26f, .72f, 1f), Shader.TileMode.CLAMP)
        sheenPaint.shader = LinearGradient(0f, 0f, 0f, h * .65f,
            Color.argb(100, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        // 外沿偏冷、内沿偏亮，形成有厚度的玻璃边缘；下沿加强与浅色背景的分离。
        rimPaint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(Color.argb(68, 119, 139, 155), Color.argb(36, 139, 161, 178), Color.argb(98, 104, 126, 145)),
            floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
        innerRimPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.argb(230, 255, 255, 255), Color.argb(55, 255, 255, 255), Color.argb(130, 255, 255, 255)),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        blurred?.let { canvas.drawBitmap(it, null, backdropBounds, backdropPaint) }
        val radius = height / 2f
        canvas.drawRoundRect(glassBounds, radius, radius, tintPaint)
        canvas.drawOval(sheenBounds, sheenPaint)
        val innerRadius = innerRimBounds.height() / 2f
        canvas.drawRoundRect(innerRimBounds, innerRadius, innerRadius, innerRimPaint)
        canvas.drawRoundRect(glassBounds, radius, radius, rimPaint)
    }

    private fun updateBackdrop() {
        val bitmapWidth = ceil((width + 2f * blurPadding) / sampleSize).toInt()
        val bitmapHeight = ceil((height + 2f * blurPadding) / sampleSize).toInt()
        if (captured?.width != bitmapWidth || captured?.height != bitmapHeight) {
            captured = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            blurred = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            captureCanvas = Canvas(captured!!)
            pixels = IntArray(bitmapWidth * bitmapHeight)
            previousPixels = IntArray(pixels.size)
            scratch = IntArray(pixels.size)
        }
        content.getLocationInWindow(sourceLocation)
        getLocationInWindow(navLocation)
        val canvas = captureCanvas!!
        canvas.drawColor(PortraitStyle.background)
        val save = canvas.save()
        canvas.scale(1f / sampleSize, 1f / sampleSize)
        canvas.translate((sourceLocation[0] - navLocation[0]) + blurPadding,
            (sourceLocation[1] - navLocation[1]) + blurPadding)
        // content 与导航是同级视图，不会把导航自身、文字或高光再次采样进去。
        content.draw(canvas)
        canvas.restoreToCount(save)
        captured!!.getPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)
        if (pixels.contentEquals(previousPixels)) return
        pixels.copyInto(previousPixels)
        blur(bitmapWidth, bitmapHeight)
        blurred!!.setPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)
        // 后方内容没有变化时不刷新，避免玻璃底板自行产生持续绘制。
        invalidate()
    }

    /** 小尺寸取样做三轮可分离均值模糊，兼容本项目支持的 Android 版本。 */
    private fun blur(w: Int, h: Int) {
        val radius = 2
        val divisor = radius * 2 + 1
        repeat(3) {
            for (y in 0 until h) {
                val row = y * w
                var red = 0
                var green = 0
                var blue = 0
                for (offset in -radius..radius) {
                    val color = pixels[row + offset.coerceIn(0, w - 1)]
                    red += Color.red(color); green += Color.green(color); blue += Color.blue(color)
                }
                for (x in 0 until w) {
                    scratch[row + x] = Color.rgb(red / divisor, green / divisor, blue / divisor)
                    val removed = pixels[row + (x - radius).coerceIn(0, w - 1)]
                    val added = pixels[row + (x + radius + 1).coerceIn(0, w - 1)]
                    red += Color.red(added) - Color.red(removed)
                    green += Color.green(added) - Color.green(removed)
                    blue += Color.blue(added) - Color.blue(removed)
                }
            }
            for (x in 0 until w) {
                var red = 0
                var green = 0
                var blue = 0
                for (offset in -radius..radius) {
                    val color = scratch[offset.coerceIn(0, h - 1) * w + x]
                    red += Color.red(color); green += Color.green(color); blue += Color.blue(color)
                }
                for (y in 0 until h) {
                    pixels[y * w + x] = Color.rgb(red / divisor, green / divisor, blue / divisor)
                    val removed = scratch[(y - radius).coerceIn(0, h - 1) * w + x]
                    val added = scratch[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                    red += Color.red(added) - Color.red(removed)
                    green += Color.green(added) - Color.green(removed)
                    blue += Color.blue(added) - Color.blue(removed)
                }
            }
        }
    }
}
