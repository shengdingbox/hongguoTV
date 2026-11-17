// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.hongguotv.core.Detail
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 手机竖屏选集弹层，对齐第三方参考实现的 HongguoEpisodeSheet：
 *
 *  - 从底部升起的深色圆角面板（#171717，顶部两角 22dp），背后是半透明遮罩，点遮罩关闭
 *  - 每行 5 个数字方块，当前集用红果红 #E44B40 高亮，其余深灰 #292929
 *  - 打开时自动滚到当前集所在行（上方留两行上下文）
 *
 * 只负责展示与选择，选中后回调 MainActivity 走原有的 playEpisode，不碰播放内核。
 */
class PhoneEpisodeSheet(
    private val activity: Activity,
    private val data: Detail,
    private val current: Int,
    private val onSelect: (Int) -> Unit,
    private val onDismiss: () -> Unit,
    private var previewAspectRatio: Float? = null,
    private val onPanelTopChanged: (Int) -> Unit = {},
    private val onAnimationProgress: (Float) -> Unit = {},
    private val availableEpisodes: Set<Int>? = null,
) {
    private fun dp(v: Int) = PhoneUi.dp(activity, v)
    private fun text(v: String, size: Float, color: Int = Color.WHITE) = PhoneUi.text(activity, v, size, color)

    private val dialog = object : Dialog(activity) {
        override fun dismiss() = requestDismiss()
        fun dismissImmediately() = super.dismiss()
    }
    private var content: FrameLayout? = null
    private var panel: View? = null
    private var backdrop: View? = null
    private var motion: ValueAnimator? = null
    private var motionProgress = 0f
    private var entranceStarted = false
    private var closing = false
    private var pendingSelection: Int? = null

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setOnDismissListener {
            cancelMotion()
            val selected = pendingSelection
            pendingSelection = null
            content = null; panel = null; backdrop = null
            onDismiss()
            // 先完成收起和位置恢复，再切集，避免重建播放器打断动画。
            if (selected != null) onSelect(selected)
        }
        dialog.setContentView(build())
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setWindowAnimations(0)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            decorView.setPadding(0, 0, 0, 0)
            // 背景只由内容遮罩控制，避免系统调暗与自绘遮罩叠加。
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
        }
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        // 面板登记到统一弹窗栈，播放控制条据此判断弹窗状态
        TvDialog.register(dialog)
    }

    /** 页面退出或旧弹框替换时立即清理，不让动画继续持有旧播放器。 */
    fun dismiss() {
        pendingSelection = null
        closing = true
        cancelMotion()
        applyMotion(0f)
        if (dialog.isShowing) dialog.dismissImmediately()
    }

    private fun requestDismiss(selection: Int? = null) {
        if (!dialog.isShowing || closing) return
        if (activity.isFinishing || activity.isDestroyed) { dismiss(); return }
        closing = true
        pendingSelection = selection
        if (panel?.isLaidOut == true) {
            animateMotion(0f)
        } else {
            cancelMotion()
            applyMotion(0f)
            dialog.dismissImmediately()
        }
    }

    private fun cancelMotion() {
        val previous = motion
        motion = null
        previous?.cancel()
    }

    private fun animateMotion(target: Float) {
        cancelMotion()
        if (motionProgress == target) {
            applyMotion(target)
            if (target == 0f) dialog.dismissImmediately()
            return
        }
        val animator = ValueAnimator.ofFloat(motionProgress, target)
        motion = animator
        animator.duration = (300 * abs(target - motionProgress)).toLong().coerceAtLeast(1L)
        animator.interpolator = PathInterpolator(0.22f, 0f, 0.2f, 1f)
        animator.addUpdateListener { applyMotion(it.animatedValue as Float) }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (motion !== animation) return
                motion = null
                if (target == 0f) dialog.dismissImmediately()
            }
        })
        animator.start()
    }

    private fun applyMotion(progress: Float) {
        motionProgress = progress.coerceIn(0f, 1f)
        panel?.let { it.translationY = it.height * (1f - motionProgress) }
        backdrop?.alpha = motionProgress
        // 弹框和视频使用同一个动画进度，避免先后启动产生错位。
        onAnimationProgress(motionProgress)
    }

    fun updatePreviewAspectRatio(aspectRatio: Float?) {
        if (previewAspectRatio == aspectRatio) return
        previewAspectRatio = aspectRatio
        backdrop?.setBackgroundColor(backdropColor())
        content?.requestLayout()
    }

    // 预览视频保持原亮度；普通选集只保留轻量遮罩。
    private fun backdropColor(): Int = if (previewAspectRatio != null) Color.TRANSPARENT else Color.argb(82, 0, 0, 0)

    private fun build(): View {
        // 点击面板外关闭；横向视频预览区不覆盖深色遮罩。
        val root = FrameLayout(activity).apply {
            isClickable = true
            setOnClickListener { requestDismiss() }
        }
        backdrop = View(activity).apply {
            setBackgroundColor(backdropColor())
            alpha = 0f
        }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        content = root
        var topInsetPx = 0
        val panel = object : LinearLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
                val maxHeight = (availableHeight * .7f).toInt().coerceAtLeast(1)
                val ratio = previewAspectRatio
                val cap = if (ratio != null) {
                    val previewHeight = (MeasureSpec.getSize(widthMeasureSpec) / ratio).toInt()
                    // 优先预留完整宽度的视频，小屏幕至少留下标题和两行选集空间。
                    val minHeight = (dp(200) + paddingBottom).coerceAtMost(maxHeight)
                    (availableHeight - topInsetPx - dp(16) - previewHeight).coerceIn(minHeight, maxHeight)
                } else maxHeight
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(0x17, 0x17, 0x17))
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            }
            setOnClickListener { }
        }

        this.panel = panel
        PhoneInsets.listen(root) { topInset, bottomInset, _ ->
            topInsetPx = topInset
            panel.setPadding(0, 0, 0, bottomInset)
            panel.requestLayout()
        }
        panel.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            // 回传最终停靠位置，排除面板当前的动画位移。
            onPanelTopChanged((location[1] - view.translationY).roundToInt())
            applyMotion(motionProgress)
            if (!entranceStarted && !closing) {
                entranceStarted = true
                animateMotion(1f)
            }
        }

        // 标题行：剧名 + 「共N集 · 自动连播」+ 关闭
        val head = PhoneUi.row(activity).apply { setPadding(dp(20), dp(14), dp(8), 0) }
        val titles = PhoneUi.column(activity)
        titles.addView(text(data.series.title, 16f).apply {
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2))
        titles.addView(text("共${data.episodes.size}集 · 自动连播", 12f, Color.argb(153, 255, 255, 255)), LinearLayout.LayoutParams(-1, -2))
        head.addView(titles, LinearLayout.LayoutParams(0, -2).apply { weight = 1f })
        head.addView(text("✕", 18f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { requestDismiss() }
        }, LinearLayout.LayoutParams(-2, -2))
        panel.addView(head, LinearLayout.LayoutParams(-1, -2))

        // 数字宫格：每行 5 个，打开时滚到当前集所在行。
        // 面板先为视频预留高度，剩余空间由列表滚动；集数少时按内容收缩。
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(20)) }
        scroll.addView(grid, LinearLayout.LayoutParams(-1, -2))
        val columns = 5
        val rows = data.episodes.indices.toList().chunked(columns)
        var currentRow = 0
        rows.forEachIndexed { rowIndex, indexes ->
            if (current in indexes) currentRow = rowIndex
            val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            indexes.forEach { index ->
                line.addView(cell(index), LinearLayout.LayoutParams(0, dp(52)).apply {
                    weight = 1f; rightMargin = dp(10)
                })
            }
            repeat(columns - indexes.size) { line.addView(Space(activity), LinearLayout.LayoutParams(0, dp(52)).apply { weight = 1f; rightMargin = dp(10) }) }
            grid.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // 打开时把当前集所在行滚到可见区中部（上方留两行上下文）
        val rowPitch = dp(62)
        scroll.post { scroll.scrollTo(0, ((currentRow - 2).coerceAtLeast(0)) * rowPitch) }
        return root
    }

    /** 单个数字方块：当前集红底白字，其余深灰底。 */
    private fun cell(index: Int): TextView {
        val selected = index == current
        return text("${index + 1}", 16f, if (selected) Color.WHITE else Color.argb(224, 255, 255, 255)).apply {
            gravity = Gravity.CENTER
            setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            background = TvStyle.shape(activity, if (selected) Color.rgb(0xE4, 0x4B, 0x40) else Color.rgb(0x29, 0x29, 0x29), radius = 10)
            val available = availableEpisodes?.contains(index) != false
            isEnabled = available
            alpha = if (available) 1f else .3f
            contentDescription = "第 ${index + 1} 集" + if (!available) "，尚未缓存" else if (selected) "，当前播放" else ""
            setOnClickListener {
                requestDismiss(if (index == current) null else index)
            }
        }
    }
}
