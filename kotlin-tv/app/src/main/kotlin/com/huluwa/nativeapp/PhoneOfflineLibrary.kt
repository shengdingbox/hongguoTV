// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hongguotv.core.Detail
import com.hongguotv.core.Series
import java.util.Locale

/** 「我的」离线 Tab：进度原地更新，选集与已缓存内容共用同一份持久化档案。 */
class PhoneOfflineLibrary(private val activity: MainActivity, page: PortraitListPage) {
    private val downloads = activity.offlineDownloads
    private fun dp(value: Int) = PortraitUi.dp(activity, value)
    private fun text(value: String, size: Float = 14f, color: Int = PortraitStyle.text) = PortraitUi.text(activity, value, size, color)
    private fun lp(w: Int = -1, h: Int = -2) = LinearLayout.LayoutParams(w, h)
    val header = PortraitUi.column(activity).apply { setPadding(dp(16), dp(4), dp(16), dp(12)) }
    private val summary = text("", 13f, PortraitStyle.muted)
    private val empty = text("", 14f, PortraitStyle.muted).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(36), 0, dp(36))
    }
    val adapter = object : ListAdapter<OfflineDownloads.Item, Holder>(object : DiffUtil.ItemCallback<OfflineDownloads.Item>() {
        override fun areItemsTheSame(a: OfflineDownloads.Item, b: OfflineDownloads.Item) = a.series.id == b.series.id
        override fun areContentsTheSame(a: OfflineDownloads.Item, b: OfflineDownloads.Item) = a == b
    }) {
        private val ids = mutableMapOf<String, Long>()
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = ids.getOrPut(getItem(position).series.id) { ids.size.toLong() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder()
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    }

    init {
        header.addView(summary, lp()); header.addView(empty, lp())
        val changed: () -> Unit = { if (page.isShown) activity.renderPhoneTab() }
        page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { downloads.observe(changed); changed() }
            override fun onViewDetachedFromWindow(view: View) { downloads.unobserve(changed) }
        })
    }
    fun update(visible: Boolean) {
        header.visibility = if (visible) View.VISIBLE else View.GONE
        val items = if (visible) downloads.items else emptyList()
        val size = items.sumOf { it.size }
        summary.text = if (downloads.error.isNotBlank()) downloads.error else
            "${items.sumOf { it.downloaded.size }} 集已缓存 · ${bytes(size)}" +
                if (items.any { it.running }) " · 正在下载 ${items.count { it.running }} 部" else ""
        empty.text = when {
            downloads.error.isNotBlank() -> "离线库暂不可用，请重新打开 App 后重试"
            !downloads.loaded -> "正在读取离线缓存…"
            else -> "还没有离线缓存\n长按收藏海报，选择「离线缓存」后选集下载"
        }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(items)
    }

    inner class Holder : RecyclerView.ViewHolder(PortraitUi.column(activity)) {
        private val body = itemView as LinearLayout
        private val title = text("", 17f).apply { setTypeface(null, Typeface.BOLD) }
        private val state = text("", 13f, PortraitStyle.muted)
        private val total = progress()
        private val current = text("", 12f, PortraitStyle.muted)
        private val episode = progress()
        private val actions = PortraitUi.row(activity)
        private lateinit var item: OfflineDownloads.Item
        private fun button(label: String, action: () -> Unit) = text(label, 13f, PortraitStyle.accent).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            background = PortraitStyle.selectable(activity, android.graphics.Color.TRANSPARENT)
            setOnClickListener { action() }
            actions.addView(this, lp(0, -2).apply { weight = 1f })
        }
        private val play = button("播放") { chooseEpisode(activity, item) }
        private val select = button("选集") { pick(activity, item.series) }
        private val toggle = button("继续") { if (item.running) downloads.pause(item.series.id) else downloads.resume(item.series.id) }
        private val delete = button("删除") {
            AlertDialog.Builder(PortraitStyle.popupContext(activity)).setTitle("删除离线缓存")
                .setMessage("删除《${item.series.title}》的全部缓存和未完成文件？")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> downloads.remove(item.series.id) }.show()
        }
        init {
            body.layoutParams = RecyclerView.LayoutParams(-1, -2)
            body.setPadding(dp(16), dp(12), dp(16), dp(12))
            body.addView(title, lp()); body.addView(state, lp().apply { topMargin = dp(6) })
            body.addView(total, lp(-1, dp(4)).apply { topMargin = dp(10) })
            body.addView(current, lp().apply { topMargin = dp(8) })
            body.addView(episode, lp(-1, dp(3)).apply { topMargin = dp(6) })
            body.addView(actions, lp())
            body.setOnClickListener { chooseEpisode(activity, item) }
        }
        private fun progress() = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(PortraitStyle.accent)
            progressBackgroundTintList = ColorStateList.valueOf(PortraitStyle.outline)
        }
        fun bind(value: OfflineDownloads.Item) {
            item = value
            title.text = value.series.title
            val status = when {
                value.running && value.current == 0 -> "等待下载"
                value.running -> "下载中"
                value.message.isNotBlank() -> value.message
                value.pending.isNotEmpty() -> "已暂停，可继续下载"
                else -> "缓存完成"
            }
            state.text = "已缓存 ${value.downloaded.size}/${value.selected.size} 集 · ${bytes(value.size)} · $status"
            total.progress = value.progress
            current.visibility = if (value.running && value.current > 0) View.VISIBLE else View.GONE
            episode.visibility = current.visibility
            current.text = "第 ${value.current} 集 · ${bytes(value.bytes)} / ${bytes(value.totalBytes)}"
            episode.progress = if (value.totalBytes > 0) (value.bytes * 1000 / value.totalBytes).toInt().coerceIn(0, 1000) else 0
            play.isEnabled = value.downloaded.isNotEmpty(); play.alpha = if (play.isEnabled) 1f else .4f
            toggle.text = if (value.running) "暂停" else "继续"
            toggle.visibility = if (value.running || value.pending.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private fun bytes(value: Long): String = if (value >= 1024L * 1024 * 1024) String.format(Locale.CHINA, "%.1f GB", value / (1024.0 * 1024 * 1024))
            else String.format(Locale.CHINA, "%.1f MB", value / (1024.0 * 1024))

        fun pick(activity: MainActivity, series: Series, detail: Detail? = null) {
            val downloads = activity.offlineDownloads
            if (!downloads.loaded) { PhoneUi.toast(activity, if (downloads.error.isBlank()) "离线记录正在加载，请稍后重试" else downloads.error); return }
            fun show(data: Detail) {
                if (activity.isFinishing || activity.isDestroyed) return
                if (data.episodes.isEmpty()) { PhoneUi.toast(activity, "暂时没有可下载的分集"); return }
                val old = downloads.items.firstOrNull { it.series.id == series.id }
                val cached = old?.downloaded.orEmpty().map { it.id }.toSet()
                val selected = old?.selected.orEmpty().filter { it in data.episodes && it !in cached }.toMutableSet()
                val labels = data.episodes.mapIndexed { index, id -> "第 ${index + 1} 集" + if (id in cached) " · 已缓存" else "" }.toTypedArray()
                val checked = BooleanArray(labels.size) { data.episodes[it] in selected || data.episodes[it] in cached }
                val dialog = AlertDialog.Builder(PortraitStyle.popupContext(activity)).setTitle("选择离线分集 · ${data.series.title}")
                    .setMultiChoiceItems(labels, checked) { dialog, index, enabled ->
                        val id = data.episodes[index]
                        if (id in cached) (dialog as AlertDialog).listView.setItemChecked(index, true)
                        else if (enabled) selected += id else selected -= id
                    }.setNegativeButton("取消", null).setNeutralButton("全选", null).setPositiveButton("开始下载", null).create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        val selectable = data.episodes.filter { it !in cached }
                        val all = selectable.isNotEmpty() && selectable.all { it in selected }
                        selected.clear(); if (!all) selected.addAll(selectable)
                        data.episodes.forEachIndexed { index, id -> dialog.listView.setItemChecked(index, id in selected || id in cached) }
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text = if (all) "全选" else "清空"
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (selected.isEmpty()) { PhoneUi.toast(activity, "请选择尚未缓存的分集"); return@setOnClickListener }
                        downloads.start(data, selected.toSet())
                        dialog.dismiss()
                        activity.showOfflineLibraryPhone()
                    }
                }
                dialog.show()
            }
            if (detail != null) { show(detail); return }
            val saved = downloads.items.firstOrNull { it.series.id == series.id }
            if (saved != null) { show(Detail(saved.series, saved.episodeIds)); return }
            val loading = AlertDialog.Builder(PortraitStyle.popupContext(activity)).setMessage("正在加载分集…").setNegativeButton("取消", null).create()
            loading.show()
            downloads.detail(series) { result ->
                if (loading.isShowing && !activity.isFinishing && !activity.isDestroyed) {
                    loading.dismiss()
                    result.onSuccess(::show).onFailure { PhoneUi.toast(activity, it.message ?: "分集加载失败，请重试") }
                }
            }
        }

        private fun chooseEpisode(activity: MainActivity, item: OfflineDownloads.Item) {
            val episodes = item.downloaded.sortedBy { it.number }
            if (episodes.isEmpty()) return
            AlertDialog.Builder(PortraitStyle.popupContext(activity)).setTitle("离线播放 · ${item.series.title}")
                .setItems(episodes.map { "第 ${it.number} 集 · ${it.quality}" }.toTypedArray()) { _, index ->
                    activity.openOfflinePhone(item, episodes[index].number - 1)
                }.setNegativeButton("取消", null).show()
        }
    }
}
