// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import com.hongguotv.core.Detail

/**
 * 手机竖屏选集弹层，对齐葫芦娃红果播放页的 HongguoEpisodeSheet：
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
) {
    private fun dp(v: Int) = PhoneUi.dp(activity, v)
    private fun text(v: String, size: Float, color: Int = Color.WHITE) = PhoneUi.text(activity, v, size, color)

    private val dialog = Dialog(activity)

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setOnDismissListener { onDismiss() }
        val width = activity.resources.displayMetrics.widthPixels
        val height = activity.resources.displayMetrics.heightPixels
        dialog.setContentView(build())
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(width, height)
        }
        dialog.show()
        // 面板要能被手机遥控操作（数字宫格同样登记到统一弹窗栈）
        TvDialog.register(dialog)
    }

    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }

    private fun build(): View {
        // 深色半透明遮罩；点遮罩关闭
        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(199, 0, 0, 0))
            isClickable = true
            setOnClickListener { dismiss() }
        }

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(0x17, 0x17, 0x17))
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            }
            setOnClickListener { }
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
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(-2, -2))
        panel.addView(head, LinearLayout.LayoutParams(-1, -2))

        // 数字宫格：每行 5 个，打开时滚到当前集所在行。
        // 高度最多占屏幕 70%（减去标题行），超出由 ScrollView 滚动；集数少时按内容收缩。
        // ScrollView 没有 maxHeight，这里重写测量把上限写进 heightMeasureSpec。
        val panelHeight = (activity.resources.displayMetrics.heightPixels * .7f).toInt()
        val maxGridHeight = (panelHeight - dp(70)).coerceAtLeast(dp(160))
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = MeasureSpec.makeMeasureSpec(maxGridHeight, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, cap)
            }
        }.apply { isVerticalScrollBarEnabled = false }
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
            contentDescription = "第 ${index + 1} 集" + if (selected) "，当前播放" else ""
            setOnClickListener {
                if (index == current) { dismiss(); return@setOnClickListener }
                dismiss()
                onSelect(index)
            }
        }
    }
}
