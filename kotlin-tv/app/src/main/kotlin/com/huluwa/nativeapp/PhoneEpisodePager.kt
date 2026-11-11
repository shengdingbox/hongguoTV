// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** 手机短剧整屏纵向分页：只保留相邻封面页，当前页始终复用同一个播放器。 */
@SuppressLint("ClickableViewAccessibility")
internal class PhoneEpisodePager(
    context: Context,
    val content: FrameLayout,
    private val preview: (Int) -> View,
    private val canSwipe: () -> Boolean,
    private val onSelect: (Int) -> Boolean,
) : FrameLayout(context) {
    private val previous = FrameLayout(context)
    private val next = FrameLayout(context)
    private val configuration = ViewConfiguration.get(context)
    private val touchSlop = configuration.scaledTouchSlop
    private val flingVelocity = maxOf(configuration.scaledMinimumFlingVelocity.toFloat(), 400f * resources.displayMetrics.density)
    private var selected = -1
    private var count = 0
    private var position = 0f
    private var motion: ValueAnimator? = null
    private var selecting = false
    private var selectionRejected = false
    private var dragging = false
    private var blockedGesture = false
    private var pointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var startPosition = 0f
    private var startIndex = 0
    private var velocity: VelocityTracker? = null
    private var released = false

    init {
        clipChildren = true
        addView(previous, LayoutParams(-1, -1))
        addView(next, LayoutParams(-1, -1))
        addView(content, LayoutParams(-1, -1))
    }

    /** 手势中的切集保留当前滚动位置；选集和自动连播的相邻跳转补上同样的翻页动画。 */
    fun updateEpisode(index: Int, episodeCount: Int) {
        if (released) return
        val oldIndex = selected
        selected = index
        count = episodeCount
        bindPreview(previous, index - 1)
        bindPreview(next, index + 1)
        when {
            oldIndex < 0 -> { position = index.toFloat(); applyPosition() }
            selecting || dragging || oldIndex == index -> applyPosition()
            else -> {
                cancelMotion()
                if (abs(index - oldIndex) == 1 && height > 0) {
                    position = oldIndex.toFloat()
                    applyPosition()
                    animateTo(index, selectWhileMoving = false)
                } else {
                    position = index.toFloat()
                    applyPosition()
                }
            }
        }
    }

    private fun bindPreview(container: FrameLayout, index: Int) {
        container.removeAllViews()
        container.visibility = if (index in 0 until count) View.VISIBLE else View.INVISIBLE
        if (index in 0 until count) container.addView(preview(index), LayoutParams(-1, -1))
    }

    private fun applyPosition() {
        content.translationY = (selected - position) * height
        previous.translationY = (selected - 1 - position) * height
        next.translationY = (selected + 1 - position) * height
        // 相邻封面只作翻页预览，不重复暴露按钮给无障碍服务。
        previous.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        next.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun moveTo(value: Float, selectWhileMoving: Boolean) {
        position = value
        applyPosition()
        if (!selectWhileMoving || selectionRejected || count == 0) return
        val visibleIndex = position.roundToInt().coerceIn(0, count - 1)
        if (visibleIndex == selected) return
        selecting = true
        try {
            selectionRejected = !onSelect(visibleIndex)
        } finally {
            selecting = false
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (released) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelMotion()
            dragging = false
            blockedGesture = !canSwipe() || count < 2
            selectionRejected = false
            pointerId = event.getPointerId(0)
            downX = event.x; downY = event.y
            startPosition = position; startIndex = selected
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
        }
        velocity?.addMovement(event)
        // 双指操作不参与翻页；已经开始拖动时回到当前集。
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            blockedGesture = true
            if (dragging) animateTo(selected, selectWhileMoving = false)
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (!dragging && motion == null && position != selected.toFloat()) animateTo(selected, selectWhileMoving = false)
            dragging = false
            pointerId = -1
            velocity?.recycle(); velocity = null
        }
        return handled
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (dragging) return true
        if (blockedGesture || !canSwipe() || height <= 0 || selected < 0) return false
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val pointer = event.findPointerIndex(pointerId)
            if (pointer < 0) return false
            val dx = event.getX(pointer) - downX
            val dy = event.getY(pointer) - downY
            if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.25f) {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return true
        if (blockedGesture) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val pointer = event.findPointerIndex(pointerId)
                if (pointer < 0 || height <= 0) return true
                val raw = (startPosition - (event.getY(pointer) - downY) / height)
                    .coerceIn((startIndex - 1).toFloat(), (startIndex + 1).toFloat())
                val resisted = when {
                    raw < 0f -> raw * .18f
                    raw > count - 1 -> count - 1 + (raw - (count - 1)) * .18f
                    else -> raw
                }
                moveTo(resisted, selectWhileMoving = true)
            }
            MotionEvent.ACTION_UP -> {
                velocity?.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
                val vx = velocity?.getXVelocity(pointerId) ?: 0f
                val vy = velocity?.getYVelocity(pointerId) ?: 0f
                val target = if (abs(vy) > flingVelocity && abs(vy) > abs(vx) * 1.4f) {
                    if (vy < 0) floor(position).toInt() + 1 else ceil(position).toInt() - 1
                } else position.roundToInt()
                animateTo(target.coerceIn(maxOf(0, startIndex - 1), minOf(count - 1, startIndex + 1)), selectWhileMoving = true)
            }
            MotionEvent.ACTION_CANCEL -> animateTo(selected, selectWhileMoving = false)
        }
        return true
    }

    private fun animateTo(index: Int, selectWhileMoving: Boolean) {
        cancelMotion()
        val target = index.toFloat()
        if (position == target) {
            moveTo(target, selectWhileMoving)
            return
        }
        val animator = ValueAnimator.ofFloat(position, target)
        motion = animator
        animator.duration = (300 * abs(target - position)).toLong().coerceIn(100L, 350L)
        animator.interpolator = PathInterpolator(.22f, 0f, .2f, 1f)
        animator.addUpdateListener { moveTo(it.animatedValue as Float, selectWhileMoving) }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (motion !== animation) return
                motion = null
                // 生命周期已退出或切集未获接管时，仍吸附到实际在播的这一集。
                position = selected.toFloat()
                applyPosition()
            }
        })
        animator.start()
    }

    private fun cancelMotion() {
        val previousMotion = motion
        motion = null
        previousMotion?.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cancelMotion()
        position = selected.coerceAtLeast(0).toFloat()
        applyPosition()
    }

    fun release() {
        released = true
        cancelMotion()
        velocity?.recycle(); velocity = null
        dragging = false
    }
}
