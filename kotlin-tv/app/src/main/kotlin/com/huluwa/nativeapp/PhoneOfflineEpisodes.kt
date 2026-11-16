// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.Detail

/** 缓存选择与本地播放共用分页数字宫格，底部主操作固定，长剧不会挤掉确认按钮。 */
class PhoneOfflineEpisodes private constructor(
    private val activity: MainActivity,
    private val detail: Detail,
    private val selecting: Boolean,
) {
    private fun dp(value: Int) = PortraitUi.dp(activity, value)
    private fun lp(w: Int = -1, h: Int = -2) = LinearLayout.LayoutParams(w, h)
    private fun text(value: String, size: Float, color: Int = PortraitStyle.text) = PortraitUi.text(activity, value, size, color)
    private val downloads = activity.offlineDownloads
    private fun record() = downloads.items.firstOrNull { it.series.id == detail.series.id }
    private var cached = record()?.downloaded.orEmpty().map { it.id }.filter { it in detail.episodes }.toSet()
    private val selected = record()?.pending.orEmpty().filter { it in detail.episodes }.toMutableSet()
    private val progress = activity.phoneLibrary.progress(detail.series.id)
    private val previousIndex = detail.episodes.indexOf(progress?.episodeId)
    private val resumeIndex = when {
        progress?.completed == true -> detail.episodes.indices.firstOrNull { it > previousIndex && detail.episodes[it] in cached }
        previousIndex >= 0 && detail.episodes[previousIndex] in cached -> previousIndex
        else -> null
    } ?: detail.episodes.indexOfFirst { it in cached }.coerceAtLeast(0)
    private val pageSize = 60
    private var pageIndex = if (selecting) detail.episodes.indexOfFirst { it in selected }.coerceAtLeast(0) / pageSize else resumeIndex / pageSize
    private var columns = 6
    private val cells = linkedMapOf<Int, LinearLayout>()
    private val pageButtons = linkedMapOf<Int, TextView>()
    private val grid = PortraitUi.column(activity)
    private val content = PortraitUi.column(activity)
    private val metadata = text("", 13f, PortraitStyle.muted)
    private val selectionLabel = text("", 14f, PortraitStyle.secondaryText)
    private val selectAll = text("全选", 14f, PortraitStyle.accent).apply {
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 12)
        isFocusable = true
    }
    private val poster = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = PortraitStyle.shape(activity, PortraitStyle.raised, radius = 10)
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private lateinit var dialog: AlertDialog
    private val action = PhoneDialog.button(activity, "", primary = true) {
        if (selecting) {
            val targets = selected.filter { it !in cached }.toSet()
            if (targets.isNotEmpty()) {
                downloads.start(detail, targets)
                dialog.dismiss()
                activity.showOfflineLibraryPhone()
            }
        } else open(resumeIndex)
    }
    private val changed: () -> Unit = {
        val next = record()
        val ids = next?.downloaded.orEmpty().map { it.id }.filter { it in detail.episodes }.toSet()
        if (!selecting && next == null) dialog.dismiss()
        else if (cached != ids) {
            cached = ids
            selected.removeAll(cached)
            updateSelection()
        }
    }

    init {
        val hero = PortraitUi.row(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        hero.addView(poster, lp(dp(54), dp(72)).apply { rightMargin = dp(12) })
        hero.addView(PortraitUi.column(activity).apply {
            addView(text(detail.series.title, 17f).apply {
                setTypeface(null, Typeface.BOLD)
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            }, lp())
            addView(metadata, lp().apply { topMargin = dp(7) })
        }, lp(0, -2).apply { weight = 1f })
        content.addView(hero, lp().apply { bottomMargin = dp(14) })
        content.addView(text(if (selecting) "点选想看的分集，已缓存的无需重复下载" else "点选已缓存分集，即可离线观看",
            13f, PortraitStyle.muted).apply { setLineSpacing(dp(3).toFloat(), 1f) }, lp().apply { bottomMargin = dp(10) })
        val toolbar = PortraitUi.row(activity)
        toolbar.addView(selectionLabel, lp(0, -2).apply { weight = 1f })
        if (selecting) toolbar.addView(selectAll, lp(-2, -2))
        else toolbar.setPadding(0, dp(10), 0, dp(12))
        content.addView(toolbar, lp())
        selectAll.setOnClickListener {
            val selectable = detail.episodes.filter { it !in cached }
            val all = selectable.isNotEmpty() && selectable.all { it in selected }
            selected.clear()
            if (!all) selected.addAll(selectable)
            updateSelection()
        }
        val totalPages = (detail.episodes.size + pageSize - 1) / pageSize
        if (totalPages > 1) {
            val strip = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
            val pages = PortraitUi.row(activity)
            repeat(totalPages) { page ->
                val first = page * pageSize + 1
                val last = minOf((page + 1) * pageSize, detail.episodes.size)
                val button = text("$first–$last", 13f).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(44)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    isFocusable = true
                    setOnClickListener {
                        pageIndex = page
                        renderGrid()
                        paintPages()
                    }
                }
                pageButtons[page] = button
                pages.addView(button, lp(-2, -2).apply { rightMargin = dp(6) })
            }
            strip.addView(pages)
            content.addView(strip, lp().apply { bottomMargin = dp(12) })
            strip.post {
                pageButtons[pageIndex]?.let { strip.scrollTo((it.left - dp(12)).coerceAtLeast(0), 0) }
            }
        }
        content.addView(grid, lp())
        grid.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val fontScale = activity.resources.configuration.fontScale.coerceAtLeast(1f)
            val next = ((right - left + dp(8)) / (dp(58) * fontScale)).toInt().coerceIn(1, 6)
            if (next != columns) { columns = next; grid.post { renderGrid() } }
        }
        dialog = PhoneDialog.sheet(activity, if (selecting) "离线缓存" else "离线播放", content = content, footer = action)
        dialog.setOnDismissListener {
            downloads.unobserve(changed)
            activity.recyclePhoneCover(poster)
        }
        renderGrid()
        paintPages()
        updateSelection()
    }

    private fun show() {
        dialog.show()
        activity.updatePhoneCover(poster, detail.series.cover)
        downloads.observe(changed)
    }

    private fun paintPages() {
        pageButtons.forEach { (page, button) ->
            val on = page == pageIndex
            button.isSelected = on
            button.setTextColor(if (on) PortraitStyle.onChipSelected else PortraitStyle.text)
            button.background = PortraitStyle.selectable(activity, if (on) PortraitStyle.chipSelected else PortraitStyle.raised, radius = 20)
            button.contentDescription = "第${page * pageSize + 1}至${minOf((page + 1) * pageSize, detail.episodes.size)}集，${if (on) "已选择" else "切换分组"}"
        }
    }

    private fun renderGrid() {
        grid.removeAllViews(); cells.clear()
        val first = pageIndex * pageSize
        val last = minOf(first + pageSize, detail.episodes.size)
        (first until last).toList().chunked(columns).forEach { indices ->
            val row = PortraitUi.row(activity)
            indices.forEachIndexed { column, index ->
                val cell = PortraitUi.column(activity).apply {
                    gravity = Gravity.CENTER
                    minimumHeight = dp(60)
                    setPadding(dp(2), dp(10), dp(2), dp(10))
                    isFocusable = true
                    addView(text("${index + 1}", 16f).apply {
                        gravity = Gravity.CENTER
                        setTypeface(null, Typeface.BOLD)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, lp())
                    addView(text("", 10f).apply {
                        gravity = Gravity.CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, lp().apply { topMargin = dp(5) })
                    setOnClickListener {
                        if (selecting) {
                            val id = detail.episodes[index]
                            if (id !in cached) {
                                if (!selected.add(id)) selected.remove(id)
                                updateSelection()
                            }
                        } else open(index)
                    }
                }
                cells[index] = cell
                row.addView(cell, lp(0, -2).apply { weight = 1f; if (column > 0) leftMargin = dp(8) })
            }
            repeat(columns - indices.size) {
                row.addView(View(activity), lp(0, 0).apply { weight = 1f; leftMargin = dp(8) })
            }
            grid.addView(row, lp().apply { bottomMargin = dp(8) })
        }
        paintCells()
    }

    private fun paintCells() {
        cells.forEach { (index, cell) ->
            val id = detail.episodes[index]
            val saved = id in cached
            val on = if (selecting) id in selected && !saved else saved && index == resumeIndex
            val enabled = if (selecting) !saved else saved
            val label = when {
                saved && !selecting && index == resumeIndex -> "继续看"
                saved -> "已缓存"
                on -> "已选择"
                selecting -> "可缓存"
                else -> "未缓存"
            }
            cell.isEnabled = enabled
            cell.isSelected = on
            cell.alpha = if (!selecting && !saved) .4f else 1f
            cell.background = PortraitStyle.selectable(activity, if (on) PortraitStyle.accent else PortraitStyle.raised, radius = 10)
            (cell.getChildAt(0) as TextView).setTextColor(when { on -> PortraitStyle.onAccent; saved && selecting -> PortraitStyle.muted; else -> PortraitStyle.text })
            (cell.getChildAt(1) as TextView).apply {
                text = label
                setTextColor(when { on -> PortraitStyle.onAccent; saved -> PortraitStyle.accent; else -> PortraitStyle.muted })
            }
            cell.contentDescription = "第 ${index + 1} 集，$label"
        }
    }

    private fun updateSelection() {
        metadata.text = "共 ${detail.episodes.size} 集 · 已缓存 ${cached.size} 集"
        selectionLabel.text = if (selecting) "已选择 ${selected.size} 集" else "已缓存 ${cached.size} 集"
        val selectable = detail.episodes.filter { it !in cached }
        selectAll.text = if (selectable.isNotEmpty() && selectable.all { it in selected }) "清空" else "全选"
        selectAll.isEnabled = selectable.isNotEmpty()
        selectAll.alpha = if (selectAll.isEnabled) 1f else .4f
        action.text = when {
            !selecting -> "继续看第 ${resumeIndex + 1} 集"
            selectable.isEmpty() -> "全部分集已缓存"
            selected.isEmpty() -> "请选择缓存分集"
            else -> "缓存 ${selected.size} 集"
        }
        action.isEnabled = if (selecting) selected.isNotEmpty() else detail.episodes.getOrNull(resumeIndex)?.let { it in cached } == true
        action.alpha = if (action.isEnabled) 1f else .4f
        paintCells()
    }

    private fun open(index: Int) {
        val item = record() ?: return
        if (item.downloaded.none { it.number == index + 1 }) return
        dialog.dismiss()
        activity.openOfflinePhone(item, index)
    }

    companion object {
        fun download(activity: MainActivity, detail: Detail) = PhoneOfflineEpisodes(activity, detail, selecting = true).show()
        fun play(activity: MainActivity, item: OfflineDownloads.Item) =
            PhoneOfflineEpisodes(activity, Detail(item.series, item.episodeIds), selecting = false).show()
    }
}
