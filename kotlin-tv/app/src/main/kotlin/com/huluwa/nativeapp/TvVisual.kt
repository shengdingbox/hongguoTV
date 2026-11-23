// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

/** TV browsing visuals; drawn without bitmap assets or blur. */
internal object TvVisual {
    private val night get() = TvStyle.theme == TvStyle.Theme.NIGHT
    private fun density(context: Context) = context.resources.displayMetrics.density

    fun panel(context: Context, modal: Boolean = false) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (night) intArrayOf(Color.argb(if (modal) 250 else 160, 20, 26, 38), Color.argb(if (modal) 250 else 160, 24, 32, 47))
        else intArrayOf(Color.argb(if (modal) 250 else 210, 255, 252, 245), Color.argb(if (modal) 250 else 210, 244, 236, 223))
    ).apply {
        cornerRadius = 16 * density(context)
        setStroke(density(context).toInt().coerceAtLeast(1), if (night) Color.argb(20, 255, 255, 255) else Color.argb(38, 100, 76, 49))
    }

    // radius 默认 10dp（小卡片）；首页 hero 大卡片传 16dp 与 panel 圆角对齐，避免聚焦边框跳角
    fun card(context: Context, focused: Boolean, selected: Boolean = false, radius: Int = 10) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (night) when {
            focused -> intArrayOf(Color.argb(155, 184, 119, 82), Color.argb(235, 30, 42, 60))
            selected -> intArrayOf(Color.argb(90, 100, 54, 35), Color.argb(210, 26, 35, 50))
            else -> intArrayOf(Color.argb(205, 28, 38, 55), Color.argb(215, 18, 27, 41))
        } else when {
            focused -> intArrayOf(Color.rgb(246, 222, 194), Color.rgb(255, 250, 241))
            selected -> intArrayOf(Color.rgb(239, 224, 202), Color.rgb(250, 245, 233))
            else -> intArrayOf(Color.argb(235, 255, 253, 248), Color.argb(225, 242, 234, 220))
        }
    ).apply {
        cornerRadius = radius * density(context)
        setStroke(density(context).toInt().coerceAtLeast(1), when {
            focused -> if (night) Color.rgb(255, 172, 123) else TvStyle.accent
            selected -> TvStyle.accent
            else -> if (night) Color.argb(20, 255, 255, 255) else Color.argb(25, 100, 76, 49)
        })
    }

    fun row(context: Context, focused: Boolean, selected: Boolean = false, category: Boolean = false): Drawable = object : Drawable() {
        private val d = density(context)
        private val dark = night
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var fill: Shader? = null
        private var edge: Shader? = null
        override fun onBoundsChange(bounds: Rect) {
            rect.set(bounds)
            if (focused && bounds.width() > 0) {
                val color = TvStyle.accent
                fill = LinearGradient(rect.left, 0f, rect.right, 0f,
                    intArrayOf(withAlpha(color, if (dark) 150 else 60), withAlpha(color, 45), withAlpha(color, 8)),
                    floatArrayOf(0f, .32f, 1f), Shader.TileMode.CLAMP)
                edge = LinearGradient(rect.left, 0f, rect.right, 0f,
                    intArrayOf(withAlpha(color, 190), withAlpha(color, 55), withAlpha(color, 15)),
                    floatArrayOf(0f, .32f, 1f), Shader.TileMode.CLAMP)
            }
        }
        override fun draw(canvas: Canvas) {
            paint.style = Paint.Style.FILL
            paint.shader = fill
            paint.color = when {
                focused -> Color.WHITE
                selected -> withAlpha(TvStyle.accent, if (dark) 24 else 28)
                category -> if (dark) Color.argb(45, 28, 43, 65) else Color.argb(85, 255, 255, 255)
                else -> Color.TRANSPARENT
            }
            canvas.drawRoundRect(rect, 9 * d, 9 * d, paint)
            if (focused || selected) {
                paint.shader = edge
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = d
                paint.color = if (focused) Color.WHITE else withAlpha(TvStyle.accent, 70)
                rect.inset(d / 2, d / 2)
                canvas.drawRoundRect(rect, 9 * d, 9 * d, paint)
                rect.inset(-d / 2, -d / 2)
                paint.shader = null
                paint.style = Paint.Style.FILL
                paint.color = TvStyle.accent
                val bar = minOf(28 * d, rect.height() * .65f)
                canvas.drawRoundRect(rect.left + 6 * d, rect.centerY() - bar / 2,
                    rect.left + 9 * d, rect.centerY() + bar / 2, 2 * d, 2 * d, paint)
            }
            paint.shader = null
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    fun tab(context: Context, focused: Boolean, selected: Boolean): Drawable = object : Drawable() {
        private val d = density(context)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        override fun onBoundsChange(bounds: Rect) { rect.set(bounds) }
        override fun draw(canvas: Canvas) {
            if (focused) {
                paint.color = withAlpha(TvStyle.accent, 28)
                canvas.drawRoundRect(rect, 7 * d, 7 * d, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = d
                paint.color = withAlpha(TvStyle.accent, 150)
                rect.inset(d / 2, d / 2)
                canvas.drawRoundRect(rect, 7 * d, 7 * d, paint)
                rect.inset(-d / 2, -d / 2); paint.style = Paint.Style.FILL
            }
            if (selected) {
                paint.color = TvStyle.accent
                canvas.drawRoundRect(rect.left + 9 * d, rect.bottom - 3 * d,
                    rect.right - 9 * d, rect.bottom - d, d, d, paint)
            }
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    fun backdrop(context: Context): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dark = night
        private var main: Shader? = null
        private var glow: Shader? = null
        override fun onBoundsChange(bounds: Rect) {
            val w = bounds.width().toFloat().coerceAtLeast(1f)
            val h = bounds.height().toFloat().coerceAtLeast(1f)
            main = RadialGradient(bounds.left + w * .5f, bounds.top + h * .3f, w * .65f,
                if (dark) Color.rgb(36, 52, 80) else Color.rgb(255, 251, 240),
                if (dark) Color.rgb(7, 10, 15) else TvStyle.background, Shader.TileMode.CLAMP)
            glow = RadialGradient(bounds.left + w * .92f, bounds.top + h * .95f, w * .48f,
                Color.argb(if (dark) 22 else 15, 130, 75, 40), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        override fun draw(canvas: Canvas) {
            paint.shader = main; canvas.drawRect(bounds, paint)
            paint.shader = glow; canvas.drawRect(bounds, paint)
            paint.shader = null
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.OPAQUE
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00ffffff) or (alpha shl 24)
}

/** 独立绘制在最上层的圆角描边，避免播放画面遮住卡片四周的边框。 */
internal class TvCardBorder(context: Context, private val radius: Int = 16) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var drawableAlpha = 255

    var focused = false
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        // 线宽按半像素内缩，四边都完整落在卡片内部，避免上下沿被圆角裁剪。
        val stroke = (if (focused) 2.5f else 1.25f) * density
        val inset = stroke / 2f
        paint.strokeWidth = stroke
        paint.color = when {
            focused && TvStyle.theme == TvStyle.Theme.NIGHT -> Color.rgb(255, 172, 123)
            focused -> TvStyle.accent
            TvStyle.theme == TvStyle.Theme.NIGHT -> Color.argb(96, 255, 255, 255)
            else -> Color.argb(76, 100, 76, 49)
        }
        paint.alpha = Color.alpha(paint.color) * drawableAlpha / 255
        val corner = (radius * density - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(
            rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset,
            corner, corner, paint,
        )
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
