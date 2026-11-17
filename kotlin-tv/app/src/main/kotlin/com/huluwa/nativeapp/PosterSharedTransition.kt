// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.max
import kotlin.math.min

/** 同一 Activity 内的共享封面：在页面替换、异步加载期间独立持有封面与起点。 */
internal class PosterSharedTransition(private val root: FrameLayout, snapshot: Snapshot) {
    internal class Snapshot(
        val cover: Drawable,
        val bounds: RectF,
        val visible: RectF,
        val background: Bitmap?,
    )

    companion object {
        fun capture(root: FrameLayout, source: ImageView): Snapshot? {
            val enabled = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
                else Settings.Global.getFloat(root.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            if (!enabled || !root.isAttachedToWindow || !source.isShown || source.width <= 0 || source.height <= 0) return null
            val visible = Rect()
            if (!source.getLocalVisibleRect(visible)) return null
            val cover = source.drawable?.constantState?.newDrawable(root.resources)?.mutate() ?: return null
            val bounds = boundsInRoot(root, source)
            val clipped = RectF(visible).apply { offset(bounds.left, bounds.top) }
            // 背景只用于短暂淡出；限制尺寸，避免分配整屏的高分辨率位图。
            val scale = min(1f, 1080f / max(root.width, root.height).coerceAtLeast(1))
            val background = try {
                Bitmap.createBitmap(max(1, (root.width * scale).toInt()), max(1, (root.height * scale).toInt()), Bitmap.Config.RGB_565).also { bitmap ->
                    val alpha = source.alpha
                    try {
                        source.alpha = 0f
                        root.draw(Canvas(bitmap).apply { scale(scale, scale) })
                    } finally {
                        source.alpha = alpha
                    }
                }
            } catch (_: OutOfMemoryError) { null }
            return Snapshot(cover, bounds, clipped, background)
        }

        private fun boundsInRoot(root: View, view: View): RectF {
            val origin = IntArray(2)
            val location = IntArray(2)
            root.getLocationInWindow(origin)
            view.getLocationInWindow(location)
            val left = (location[0] - origin[0]).toFloat()
            val top = (location[1] - origin[1]).toFloat()
            return RectF(left, top, left + view.width, top + view.height)
        }
    }

    private var snapshot: Snapshot? = snapshot
    private var target: ImageView? = null
    private var animator: ValueAnimator? = null
    private var pendingObserver: ViewTreeObserver? = null
    private var pendingDraw: ViewTreeObserver.OnPreDrawListener? = null
    private val overlay = CoverOverlay(snapshot)
    private val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) cancel()
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) = cancel()
    }

    init {
        overlay.layout(0, 0, root.width, root.height)
        root.overlay.add(overlay)
        root.addOnLayoutChangeListener(layoutListener)
        root.addOnAttachStateChangeListener(attachListener)
    }

    /** 加载外壳与正式详情可以先后接管落点，动画持续进行且不重新起播。 */
    fun bind(poster: ImageView) {
        val current = snapshot ?: return
        removePendingDraw()
        target?.alpha = 1f
        target = poster
        poster.setImageDrawable(current.cover.constantState?.newDrawable(root.resources)?.mutate())
        poster.alpha = 0f
        val observer = root.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                removePendingDraw()
                if (target !== poster || !poster.isAttachedToWindow || poster.width <= 0 || poster.height <= 0) {
                    cancel()
                    return true
                }
                overlay.destination.set(boundsInRoot(root, poster))
                overlay.invalidate()
                if (animator == null) {
                    animator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 360L
                        interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
                        addUpdateListener {
                            target?.takeIf { poster -> poster.isAttachedToWindow }?.let { poster ->
                                overlay.destination.set(boundsInRoot(root, poster))
                            }
                            overlay.progress = it.animatedValue as Float
                            overlay.invalidate()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) = this@PosterSharedTransition.cancel()
                        })
                    }
                    animator?.start()
                }
                return true
            }
        }
        pendingObserver = observer
        pendingDraw = listener
        observer.addOnPreDrawListener(listener)
    }

    private fun removePendingDraw() {
        pendingDraw?.let { listener ->
            val observer = pendingObserver?.takeIf { it.isAlive } ?: root.viewTreeObserver
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        pendingObserver = null
        pendingDraw = null
    }

    fun cancel() {
        removePendingDraw()
        animator?.let { it.removeAllListeners(); it.cancel() }
        animator = null
        target?.alpha = 1f
        target = null
        root.removeOnLayoutChangeListener(layoutListener)
        root.removeOnAttachStateChangeListener(attachListener)
        root.overlay.remove(overlay)
        overlay.snapshot = null
        snapshot = null
    }

    private inner class CoverOverlay(var snapshot: Snapshot?) : View(root.context) {
        var progress = 0f
        val destination = RectF(snapshot!!.bounds)
        private val bounds = RectF()
        private val visible = RectF()
        private val path = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val radius = 10f * resources.displayMetrics.density

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            val current = snapshot ?: return
            current.background?.let { bitmap ->
                paint.alpha = (255 * (1f - progress)).toInt()
                canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), paint)
            }
            fun interpolate(start: RectF, end: RectF, out: RectF) {
                out.set(start.left + (end.left - start.left) * progress,
                    start.top + (end.top - start.top) * progress,
                    start.right + (end.right - start.right) * progress,
                    start.bottom + (end.bottom - start.bottom) * progress)
            }
            interpolate(current.bounds, destination, bounds)
            interpolate(current.visible, destination, visible)
            path.rewind()
            path.addRoundRect(bounds, radius, radius, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipRect(visible)
            canvas.clipPath(path)
            val imageWidth = current.cover.intrinsicWidth.coerceAtLeast(1)
            val imageHeight = current.cover.intrinsicHeight.coerceAtLeast(1)
            val scale = max(bounds.width() / imageWidth, bounds.height() / imageHeight)
            canvas.translate(bounds.centerX() - imageWidth * scale / 2f, bounds.centerY() - imageHeight * scale / 2f)
            canvas.scale(scale, scale)
            current.cover.setBounds(0, 0, imageWidth, imageHeight)
            current.cover.draw(canvas)
            canvas.restoreToCount(save)
        }
    }
}
