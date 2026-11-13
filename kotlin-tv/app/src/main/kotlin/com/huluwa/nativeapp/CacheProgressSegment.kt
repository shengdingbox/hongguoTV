// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

/** 缓存条的一段：只平滑显示进度，下载与缓存完成状态仍以磁盘缓存为准。 */
class CacheProgressSegment(context: Context) : View(context) {
    var cacheKey: Pair<String, Int>? = null
        private set
    var displayedProgress = 0f
        private set

    private var targetProgress = 0f
    private var animator: ValueAnimator? = null
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TvStyle.accent
        alpha = (255 * .45f).toInt()
    }

    init {
        setBackgroundColor(Color.argb(32, 255, 255, 255))
    }

    fun updateProgress(key: Pair<String, Int>, progress: Float, initialProgress: Float) {
        val target = progress.coerceIn(0f, 1f)
        if (cacheKey != key) {
            cancelAnimation()
            cacheKey = key
            displayedProgress = initialProgress.coerceIn(0f, target)
            targetProgress = 0f
            invalidate()
        }
        if (targetProgress == target) return
        targetProgress = target
        // 缓存失效时立即回退，不能继续显示已经不存在的缓存。
        if (displayedProgress > target) {
            displayedProgress = target
            invalidate()
        }
        animateProgress()
    }

    private fun animateProgress() {
        cancelAnimation()
        if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE || displayedProgress == targetProgress) return
        animator = ValueAnimator.ofFloat(displayedProgress, targetProgress).apply {
            // 全程约 1.6 秒；后续回调从当前画面接着走，重复的目标不会重启动画。
            duration = ((targetProgress - displayedProgress) * 1_600).toLong().coerceAtLeast(160L)
            interpolator = LinearInterpolator()
            addUpdateListener {
                displayedProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun cancelAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width * displayedProgress, height.toFloat(), fillPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animateProgress()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown) animateProgress() else cancelAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) animateProgress() else cancelAnimation()
    }

    override fun onDetachedFromWindow() {
        cancelAnimation()
        super.onDetachedFromWindow()
    }
}
