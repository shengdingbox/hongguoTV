// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView

/** TV side sheet adapted from N3urda/hongguoTV; only selecting a cell changes the episode. */
class EpisodePanel(
    private val activity: Activity, private val count: Int, private val current: Int,
    private val locate: (Int, View, (Int) -> Unit) -> Unit,
    private val select: (Int) -> Unit, private val closed: () -> Unit,
    private val lastWatched: Int? = null, private val lastCompleted: Boolean = false,
) {
    private val dialog = Dialog(activity)
    private val touch = TouchControls.isHandheld(activity)
    private val columns = 5
    private val lastGroup = ((count - 1).coerceAtLeast(0) / 20)
    private var group = (current.coerceAtLeast(0) / 20).coerceAtMost(lastGroup)
    private var focused = current.coerceIn(0, (count - 1).coerceAtLeast(0))
    private var initialFocus: View? = null
    private val compact = activity.resources.displayMetrics.heightPixels / activity.resources.displayMetrics.density < 430
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 16f, color: Int = TvStyle.text) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
    }
    private fun button(value: String, active: Boolean = false, action: () -> Unit) = label(value, 14f).apply {
        id = View.generateViewId(); gravity = Gravity.CENTER
        minHeight = dp(if (compact) 36 else 42)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(8), dp(7), dp(8), dp(7))
        isFocusable = true; isFocusableInTouchMode = true; isClickable = true; isSelected = active
        TouchControls.focusOnTouch(this, touch)
        fun paint(focus: Boolean) {
            background = TvVisual.card(activity, focus, active)
            setTextColor(if (active) TvStyle.accent else TvStyle.text)
            setTypeface(null, if (focus || active) Typeface.BOLD else Typeface.NORMAL)
        }
        paint(false)
        setOnFocusChangeListener { _, hasFocus -> paint(hasFocus) }
        setOnClickListener { action() }
    }

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setOnDismissListener { closed() }
        render()
        dialog.show()
        // The phone remote also navigates this independent Dialog window.
        TvDialog.register(dialog)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
            setDimAmount(.22f)
            attributes = attributes.apply { x = (activity.resources.displayMetrics.widthPixels * .05f).toInt() }
            setLayout(minOf(dp(520), (activity.resources.displayMetrics.widthPixels * .8f).toInt()),
                (activity.resources.displayMetrics.heightPixels * .9f).toInt())
        }
        initialFocus?.post { initialFocus?.requestFocus() }
    }

    fun dismiss() = dialog.dismiss()

    private fun render() {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(if (compact) 10 else 14), dp(14), dp(if (compact) 10 else 14))
            background = TvVisual.panel(activity, modal = true)
        }
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label("选集", 20f).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(label(if (count > 0) "正在看第 ${current + 1} 集" else "暂无剧集", 13f, TvStyle.accent))
        content.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(4); rightMargin = dp(4); bottomMargin = dp(if (compact) 8 else 12) })

        val actions = LinearLayout(activity)
        val close = button("关闭") { dismiss() }
        lateinit var jump: TextView
        jump = button("跳转集数") {
            if (count > 0) locate(focused, jump) { target ->
                focused = target.coerceIn(0, count - 1); group = focused / 20; render()
            }
        }.apply { isEnabled = count > 0; isFocusable = isEnabled; alpha = if (isEnabled) 1f else .4f }
        listOf(close, jump).forEach { actions.addView(it, cellParams()) }
        content.addView(actions)

        val paging = LinearLayout(activity)
        val previous = button("‹ 上一组") {
            if (group > 0) { group--; focused = group * 20; render() }
        }.apply { isEnabled = group > 0; isFocusable = isEnabled; alpha = if (isEnabled) 1f else .4f }
        val next = button("下一组 ›") {
            if (group < lastGroup) { group++; focused = group * 20; render() }
        }.apply { isEnabled = group < lastGroup; isFocusable = isEnabled; alpha = if (isEnabled) 1f else .4f }
        listOf(previous, next).forEach { paging.addView(it, cellParams()) }
        content.addView(paging)
        content.addView(label(if (count > 0) "第 ${group * 20 + 1}—${minOf((group + 1) * 20, count)} 集  /  共 $count 集" else "暂时没有可选择的剧集", 12f, TvStyle.muted).apply {
            setPadding(dp(4), dp(7), dp(4), dp(7))
        })

        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(grid)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val cells = mutableListOf<TextView>()
        (group * 20 until minOf((group + 1) * 20, count)).toList().chunked(columns).forEach { indexes ->
            val row = LinearLayout(activity)
            indexes.forEach { index ->
                val marker = when {
                    index == current -> "当前"
                    index == lastWatched -> if (lastCompleted) "已看" else "上次"
                    else -> ""
                }
                val cell = button("${index + 1}", index == current) { select(index) }.apply {
                    textSize = 18f; maxLines = 2
                    minHeight = dp(if (compact) 44 else 54)
                    setPadding(dp(4), dp(5), dp(4), dp(5))
                    text = if (marker.isBlank()) "${index + 1}" else {
                        val value = "${index + 1}\n$marker"
                        SpannableString(value).apply { setSpan(RelativeSizeSpan(.58f), value.indexOf('\n') + 1, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    }
                    contentDescription = "第 ${index + 1} 集" + when {
                        index == current -> "，当前播放"
                        marker.isNotBlank() -> "，${marker}观看"
                        else -> ""
                    }
                    val paint = onFocusChangeListener
                    setOnFocusChangeListener { view, hasFocus ->
                        paint?.onFocusChange(view, hasFocus)
                        if (hasFocus) focused = index
                    }
                }
                row.addView(cell, cellParams()); cells += cell
            }
            repeat(columns - indexes.size) { row.addView(Space(activity), cellParams(height = 1)) }
            grid.addView(row)
        }
        cells.forEachIndexed { i, cell ->
            cell.nextFocusLeftId = cells[if (i % columns == 0) i else i - 1].id
            cell.nextFocusRightId = cells[if (i % columns == columns - 1 || i == cells.lastIndex) i else i + 1].id
            cell.nextFocusUpId = if (i >= columns) cells[i - columns].id else if (i < (columns + 1) / 2 && previous.isFocusable) previous.id else if (next.isFocusable) next.id else jump.id
            cell.nextFocusDownId = cells[if (i + columns < cells.size) i + columns else if (i / columns < cells.lastIndex / columns) cells.lastIndex else i].id
        }
        val first = cells.firstOrNull()?.id ?: close.id
        val lastColumn = cells.getOrNull(minOf(columns - 1, cells.lastIndex))?.id ?: close.id
        previous.nextFocusUpId = close.id; next.nextFocusUpId = jump.id
        previous.nextFocusDownId = first; next.nextFocusDownId = lastColumn
        previous.nextFocusLeftId = previous.id; previous.nextFocusRightId = if (next.isFocusable) next.id else previous.id
        next.nextFocusLeftId = if (previous.isFocusable) previous.id else next.id; next.nextFocusRightId = next.id
        close.nextFocusDownId = if (previous.isFocusable) previous.id else first
        jump.nextFocusDownId = if (next.isFocusable) next.id else lastColumn
        close.nextFocusUpId = close.id; jump.nextFocusUpId = jump.id
        close.nextFocusLeftId = close.id; close.nextFocusRightId = if (jump.isFocusable) jump.id else close.id
        jump.nextFocusLeftId = close.id; jump.nextFocusRightId = jump.id
        if (!compact) content.addView(label("方向键选择  ·  确定播放  ·  返回收起", 11f, TvStyle.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
        })
        dialog.setContentView(content)
        initialFocus = if (cells.isNotEmpty()) cells[(focused - group * 20).coerceIn(0, cells.lastIndex)] else close
        initialFocus?.requestFocus()
    }

    private fun cellParams(height: Int = -2) = LinearLayout.LayoutParams(0, height, 1f).apply {
        setMargins(dp(3), dp(3), dp(3), dp(3))
    }
}
