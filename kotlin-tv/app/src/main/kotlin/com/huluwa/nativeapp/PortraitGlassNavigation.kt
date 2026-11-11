// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.animation.ValueAnimator
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
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

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
    private val selectionBounds = RectF()
    private val selectionTarget = RectF()
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * .65f
    }
    private var selectedIndex = -1
    private var pendingFromIndex = -1
    private var selectionAnimator: ValueAnimator? = null
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
        selectionAnimator?.cancel()
        selectionAnimator = null
        selectionBounds.set(selectionTarget)
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

    /** 页面切换会复用或重建底板，均从上一个可见 Tab 的位置开始过渡。 */
    fun selectItem(index: Int, fromIndex: Int = -1) {
        if (index !in 0 until childCount) return
        selectedIndex = index
        pendingFromIndex = fromIndex
        if (getChildAt(index).width > 0) updateSelection()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateSelection()
    }

    private fun itemBounds(index: Int): RectF {
        val item = getChildAt(index)
        return RectF(item.left + 2f * density, item.top + density,
            item.right - 2f * density, item.bottom - density)
    }

    private fun updateSelection() {
        if (selectedIndex !in 0 until childCount || getChildAt(selectedIndex).width == 0) return
        val target = itemBounds(selectedIndex)
        val fromIndex = pendingFromIndex
        pendingFromIndex = -1
        val animate = fromIndex in 0 until childCount && fromIndex != selectedIndex
        if (!animate && target == selectionTarget && !selectionBounds.isEmpty) return
        selectionAnimator?.cancel()
        selectionTarget.set(target)
        if (!animate) {
            selectionBounds.set(target)
            invalidate()
            return
        }
        val origin = itemBounds(fromIndex)
        selectionBounds.set(origin)
        val stretch = (abs(target.centerX() - origin.centerX()) * .08f).coerceAtMost(6f * density)
        selectionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320L
            interpolator = PathInterpolator(.2f, .85f, .25f, 1f)
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                val expansion = sin(PI * progress).toFloat() * stretch
                selectionBounds.set(
                    (origin.left + (target.left - origin.left) * progress - expansion).coerceAtLeast(paddingLeft.toFloat()),
                    origin.top + (target.top - origin.top) * progress,
                    (origin.right + (target.right - origin.right) * progress + expansion).coerceAtMost((width - paddingRight).toFloat()),
                    origin.bottom + (target.bottom - origin.bottom) * progress,
                )
                invalidate()
            }
            start()
        }
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
        // iOS 的选中底板为中性浅灰玻璃，绿色只用于图标与文字。
        selectionPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.argb(42, 97, 102, 109), Color.argb(50, 117, 122, 129), Color.argb(58, 139, 144, 151)),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        selectionRimPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.argb(95, 255, 255, 255), Color.argb(10, 255, 255, 255), Color.argb(12, 0, 0, 0)),
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
        if (!selectionBounds.isEmpty) {
            val selectionRadius = selectionBounds.height() / 2f
            canvas.drawRoundRect(selectionBounds, selectionRadius, selectionRadius, selectionPaint)
            canvas.drawRoundRect(selectionBounds, selectionRadius, selectionRadius, selectionRimPaint)
        }
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
