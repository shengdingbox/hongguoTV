// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.CompoundButton

/** 仅供手机设置页使用，保留系统开关的点击、键盘和无障碍语义。 */
internal class PhoneSettingsSwitch(context: Context) : CompoundButton(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private var progress = if (isChecked) 1f else 0f
    private var motion: ValueAnimator? = null
    private var ready = false

    init {
        buttonDrawable = null
        background = null
        isSaveEnabled = false
        isClickable = true
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        ready = true
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize((55 * density).toInt(), widthMeasureSpec),
            resolveSize((44 * density).toInt(), heightMeasureSpec))
    }

    override fun setChecked(checked: Boolean) {
        val changed = checked != isChecked
        super.setChecked(checked)
        if (!ready || !changed) return
        motion?.cancel()
        val target = if (checked) 1f else 0f
        if (!isAttachedToWindow) {
            progress = target
            invalidate()
            return
        }
        motion = ValueAnimator.ofFloat(progress, target).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = 51 * density
        val h = 31 * density
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        track.set(left, top, left + w, top + h)
        val off = if (PortraitStyle.isDark) Color.rgb(57, 57, 61) else Color.rgb(233, 233, 235)
        val on = Color.rgb(52, 199, 89)
        paint.clearShadowLayer()
        paint.color = Color.rgb(
            (Color.red(off) + (Color.red(on) - Color.red(off)) * progress).toInt(),
            (Color.green(off) + (Color.green(on) - Color.green(off)) * progress).toInt(),
            (Color.blue(off) + (Color.blue(on) - Color.blue(off)) * progress).toInt())
        canvas.drawRoundRect(track, h / 2, h / 2, paint)
        val radius = 13.5f * density
        val center = left + h / 2 + (w - h) * progress
        paint.color = Color.WHITE
        paint.setShadowLayer(2 * density, 0f, density, Color.argb(38, 0, 0, 0))
        canvas.drawCircle(center, top + h / 2, radius, paint)
        paint.clearShadowLayer()
    }

    override fun onDetachedFromWindow() {
        motion?.cancel()
        motion = null
        progress = if (isChecked) 1f else 0f
        super.onDetachedFromWindow()
    }
}

/** 设置页账号卡片的本机占位头像，不依赖外部图片。 */
internal class PhoneSettingsAvatar(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shoulders = RectF()

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        paint.color = if (PortraitStyle.isDark) Color.rgb(99, 99, 104) else Color.rgb(174, 174, 178)
        canvas.drawCircle(cx, cy, size / 2, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy - size * .13f, size * .135f, paint)
        shoulders.set(cx - size * .245f, cy + size * .06f, cx + size * .245f, cy + size * .32f)
        canvas.drawRoundRect(shoulders, size * .16f, size * .16f, paint)
    }
}
