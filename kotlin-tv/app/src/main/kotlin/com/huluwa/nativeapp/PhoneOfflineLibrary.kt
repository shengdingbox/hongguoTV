// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
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

/** 离线列表沿用竖屏海报、圆角分组和强调色；进度更新保留封面与卡片实例。 */
class PhoneOfflineLibrary(
    private val activity: MainActivity,
    page: PortraitListPage,
    private val loadCover: (ImageView, String) -> Unit,
) {
    private val downloads = activity.offlineDownloads
    private fun dp(value: Int) = PortraitUi.dp(activity, value)
    private fun text(value: String, size: Float = 14f, color: Int = PortraitStyle.text) = PortraitUi.text(activity, value, size, color)
    private fun lp(w: Int = -1, h: Int = -2) = LinearLayout.LayoutParams(w, h)
    val header = PortraitUi.column(activity).apply { setPadding(dp(16), 0, dp(16), dp(8)) }
    private val expandedSeries = mutableSetOf<String>()
    private val concurrencyValue = text("", 14f, PortraitStyle.accent)
    private val downloadSettings = PortraitUi.row(activity).apply {
        minimumHeight = dp(44)
        setPadding(dp(12), dp(6), dp(10), dp(6))
        background = PortraitStyle.selectable(activity, PortraitStyle.surface, radius = 14)
        isFocusable = true
        addView(text("同时下载", 14f), lp(0, -2).apply { weight = 1f })
        addView(concurrencyValue, lp(-2, -2).apply { leftMargin = dp(10) })
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(PortraitStyle.muted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, lp(dp(16), dp(18)).apply { leftMargin = dp(6) })
        setOnClickListener { configureConcurrency(activity) }
    }
    private val concurrencyHint = text("已降低并发数，正在下载的分集完成后生效", 12f, PortraitStyle.muted).apply {
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private val warning = text("", 13f, PortraitStyle.secondaryText).apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setLineSpacing(dp(3).toFloat(), 1f)
        background = PortraitStyle.shape(activity, PortraitStyle.surface, radius = 14)
    }
    private val empty = PortraitUi.emptyState(activity, R.drawable.ic_download, "还没有离线缓存",
        "在剧集详情页点右上角缓存按钮\n选好分集，好剧随时看")
    private val loading = PortraitUi.row(activity).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(48), 0, dp(48))
        addView(ProgressBar(activity).apply {
            indeterminateTintList = ColorStateList.valueOf(PortraitStyle.accent)
        }, lp(dp(22), dp(22)).apply { rightMargin = dp(12) })
        addView(text("正在读取离线缓存…", 14f, PortraitStyle.muted), lp(-2, -2))
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
        override fun onViewAttachedToWindow(holder: Holder) { super.onViewAttachedToWindow(holder); holder.loadImage() }
        override fun onViewRecycled(holder: Holder) { holder.recycleImage(); super.onViewRecycled(holder) }
    }

    init {
        header.addView(downloadSettings, lp())
        header.addView(concurrencyHint, lp().apply { topMargin = dp(6) })
        header.addView(warning, lp())
        header.addView(loading, lp()); header.addView(empty, lp())
        val changed: () -> Unit = { if (page.isShown) activity.renderPhoneTab() }
        page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { downloads.observe(changed); changed() }
            override fun onViewDetachedFromWindow(view: View) { downloads.unobserve(changed) }
        })
    }

    fun update(visible: Boolean) {
        header.visibility = if (visible) View.VISIBLE else View.GONE
        val items = if (visible) downloads.items else emptyList()
        val running = items.sumOf { it.transfers.size }
        concurrencyValue.text = "${downloads.concurrency} 集"
        downloadSettings.contentDescription = "同时下载 ${downloads.concurrency} 集，调整下载并发数"
        concurrencyHint.visibility = if (running > downloads.concurrency) View.VISIBLE else View.GONE
        warning.text = downloads.error
        warning.visibility = if (downloads.error.isNotBlank()) View.VISIBLE else View.GONE
        loading.visibility = if (!downloads.loaded && downloads.error.isBlank()) View.VISIBLE else View.GONE
        empty.visibility = if (downloads.loaded && downloads.error.isBlank() && items.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(items)
    }

    inner class Holder : RecyclerView.ViewHolder(FrameLayout(activity)) {
        private val card = PortraitUi.column(activity)
        private val cover = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = PortraitStyle.shape(activity, PortraitStyle.raised, radius = 8)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val title = text("", 16f).apply {
            setTypeface(null, Typeface.BOLD)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        private val metadata = text("", 12f, PortraitStyle.muted)
        private val status = text("", 12f, PortraitStyle.accent).apply {
            setPadding(0, dp(4), dp(6), dp(4))
            background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 6)
            setOnClickListener { toggleTransfers() }
        }
        private val percent = text("", 11f, PortraitStyle.muted)
        private val total = progress()
        private val transferHost = PortraitUi.column(activity)
        private val transferViews = linkedMapOf<String, Triple<LinearLayout, TextView, ProgressBar>>()
        private val problem = text("", 12f, PortraitStyle.secondaryText).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1f)
            setOnClickListener { PhoneDialog.info(activity, "下载遇到问题", item.message).show() }
        }
        private val actions = PortraitUi.row(activity)
        private lateinit var item: OfflineDownloads.Item
        private var boundSeries = ""
        private var boundCover = ""
        private val primaryLabel = text("", 13f, PortraitStyle.onAccent).apply {
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        private val primaryIcon = ImageView(activity).apply {
            setColorFilter(PortraitStyle.onAccent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val primary = PortraitUi.row(activity).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(44)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = PortraitStyle.selectable(activity, PortraitStyle.accent, radius = 22)
            isFocusable = true
            addView(primaryIcon, lp(dp(16), dp(16)).apply { rightMargin = dp(4) })
            addView(primaryLabel, lp(-2, -2))
            setOnClickListener {
                when {
                    item.downloaded.isNotEmpty() -> chooseEpisode(activity, item)
                    item.running -> downloads.pause(item.series.id)
                    item.pending.isNotEmpty() -> downloads.resume(item.series.id)
                    else -> pick(activity, item.series)
                }
            }
        }
        private val secondary = text("", 13f, PortraitStyle.accent).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = PortraitStyle.selectable(activity, PortraitStyle.raised, radius = 22)
            isFocusable = true
            setOnClickListener {
                if (item.downloaded.isNotEmpty() && (item.running || item.pending.isNotEmpty())) {
                    if (item.running) downloads.pause(item.series.id) else downloads.resume(item.series.id)
                } else pick(activity, item.series)
            }
        }
        private val more = ImageView(activity).apply {
            setImageResource(R.drawable.ic_more)
            setColorFilter(PortraitStyle.muted)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 22)
            isFocusable = true
            setOnClickListener { manage() }
        }

        init {
            itemView.layoutParams = RecyclerView.LayoutParams(-1, -2)
            (itemView as FrameLayout).apply {
                setPadding(dp(16), 0, dp(16), dp(8))
                addView(card, FrameLayout.LayoutParams(-1, -2))
            }
            card.apply {
                setPadding(dp(12), dp(12), dp(12), dp(10))
                background = PortraitStyle.shape(activity, PortraitStyle.surface, radius = 16)
            }
            val heading = PortraitUi.row(activity).apply { gravity = Gravity.TOP }
            heading.addView(cover, lp(dp(54), dp(72)).apply { rightMargin = dp(10) })
            heading.addView(PortraitUi.column(activity).apply {
                addView(title, lp())
                addView(metadata, lp().apply { topMargin = dp(4) })
                val line = PortraitUi.row(activity)
                line.addView(status, lp(0, -2).apply { weight = 1f })
                line.addView(percent, lp(-2, -2).apply { leftMargin = dp(6) })
                addView(line, lp().apply { topMargin = dp(2) })
                addView(total, lp(-1, dp(3)).apply { topMargin = dp(1) })
            }, lp(0, -2).apply { weight = 1f })
            card.addView(heading, lp())
            card.addView(transferHost, lp())
            card.addView(problem, lp().apply { topMargin = dp(6) })
            actions.addView(primary, lp(0, -2).apply { weight = 1f })
            actions.addView(secondary, lp(0, -2).apply { weight = 1f; leftMargin = dp(8) })
            actions.addView(more, lp(dp(44), dp(44)).apply { leftMargin = dp(4) })
            card.addView(actions, lp().apply { topMargin = dp(8) })
            cover.setOnClickListener { chooseEpisode(activity, item) }
            title.setOnClickListener { chooseEpisode(activity, item) }
        }

        private fun progress() = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(PortraitStyle.accent)
            progressBackgroundTintList = ColorStateList.valueOf(PortraitStyle.outline)
        }

        fun loadImage() { if (boundSeries.isNotBlank()) loadCover(cover, boundCover) }
        fun recycleImage() { activity.recyclePhoneCover(cover); boundSeries = ""; boundCover = "" }

        private fun toggleTransfers() {
            if (item.transfers.isEmpty()) return
            if (!expandedSeries.add(item.series.id)) expandedSeries.remove(item.series.id)
            bind(item)
        }

        private fun manage() {
            val current = item
            val choices = mutableListOf("选择缓存分集")
            val progressChoice = if (current.transfers.isNotEmpty()) {
                choices += if (current.series.id in expandedSeries) "收起各集进度" else "展开各集进度"
                choices.lastIndex
            } else -1
            choices += "删除离线缓存"
            PhoneDialog.choose(activity, "管理离线缓存", choices, { choice ->
                when (choice) {
                    0 -> pick(activity, current.series)
                    progressChoice -> {
                        if (!expandedSeries.add(current.series.id)) expandedSeries.remove(current.series.id)
                        if (item.series.id == current.series.id) bind(item)
                    }
                    else -> {
                        lateinit var confirmation: AlertDialog
                        val buttons = PortraitUi.column(activity)
                        buttons.addView(PhoneDialog.button(activity, "删除缓存", destructive = true) {
                            confirmation.dismiss()
                            expandedSeries.remove(current.series.id)
                            downloads.remove(current.series.id)
                        }, lp())
                        buttons.addView(PhoneDialog.button(activity, "取消") { confirmation.dismiss() }, lp().apply { topMargin = dp(10) })
                        confirmation = PhoneDialog.sheet(activity, "删除离线缓存",
                            "将删除《${current.series.title}》的全部缓存和未完成文件，已缓存内容占用 ${bytes(current.size)}。", buttons)
                        confirmation.show()
                    }
                }
            }).show()
        }

        fun bind(value: OfflineDownloads.Item) {
            item = value
            if (boundSeries != value.series.id || boundCover != value.series.cover) {
                if (boundSeries != value.series.id) activity.recyclePhoneCover(cover)
                boundSeries = value.series.id; boundCover = value.series.cover
                loadImage()
            }
            title.text = value.series.title
            metadata.text = "${value.downloaded.size} / ${value.selected.size} 集 · ${bytes(value.size)}"
            val pending = value.pending.isNotEmpty()
            val failed = !value.running && pending && value.message.isNotBlank() && !value.message.startsWith("已暂停")
            if (value.transfers.isEmpty()) expandedSeries.remove(value.series.id)
            val expanded = value.series.id in expandedSeries
            val state = when {
                value.running && value.transfers.isEmpty() -> "排队中"
                value.running && value.transfers.size == 1 -> "第 ${value.transfers.first().number} 集下载中"
                value.running -> "${value.transfers.size} 集同时下载"
                failed -> "下载遇到问题"
                pending -> "已暂停"
                else -> "缓存完成"
            }
            status.text = state + if (value.transfers.isNotEmpty()) if (expanded) " ▴" else " ▾" else ""
            status.isEnabled = value.transfers.isNotEmpty()
            status.isFocusable = status.isEnabled
            status.contentDescription = state + if (value.transfers.isNotEmpty())
                if (expanded) "，点击收起各集进度" else "，点击展开各集进度" else ""
            status.setTextColor(if (failed) PortraitStyle.secondaryText else PortraitStyle.accent)
            val showProgress = value.running || pending
            percent.visibility = if (showProgress) View.VISIBLE else View.GONE
            total.visibility = percent.visibility
            percent.text = "${value.progress / 10}%"
            total.progress = value.progress
            total.contentDescription = "总进度 ${value.progress / 10}%"
            val transfers = if (expanded) value.transfers else emptyList()
            val active = transfers.map { it.id }.toSet()
            transferViews.keys.filter { it !in active }.forEach { id ->
                transferViews.remove(id)?.let { transferHost.removeView(it.first) }
            }
            transfers.forEachIndexed { index, transfer ->
                val (row, label, bar) = transferViews.getOrPut(transfer.id) {
                    val row = PortraitUi.column(activity)
                    val label = text("", 11f, PortraitStyle.muted)
                    val bar = progress()
                    row.addView(label, lp().apply { topMargin = dp(7) })
                    row.addView(bar, lp(-1, dp(2)).apply { topMargin = dp(4) })
                    Triple(row, label, bar)
                }
                if (transferHost.indexOfChild(row) != index) {
                    transferHost.removeView(row)
                    transferHost.addView(row, index, lp())
                }
                label.text = "第 ${transfer.number} 集 · " + if (transfer.totalBytes > 0)
                    "${bytes(transfer.bytes)} / ${bytes(transfer.totalBytes)}" else "正在准备…"
                bar.isIndeterminate = transfer.totalBytes <= 0
                bar.indeterminateTintList = ColorStateList.valueOf(PortraitStyle.accent)
                bar.progress = if (transfer.totalBytes > 0) (transfer.bytes * 1000 / transfer.totalBytes).toInt().coerceIn(0, 1000) else 0
            }
            transferHost.visibility = if (active.isEmpty()) View.GONE else View.VISIBLE
            problem.text = value.message
            problem.visibility = if (failed) View.VISIBLE else View.GONE
            val retryLabel = if (failed) "重试下载" else "继续下载"
            val hasVideo = value.downloaded.isNotEmpty()
            primaryLabel.text = when {
                hasVideo -> "播放缓存"
                value.running -> "暂停下载"
                pending -> retryLabel
                else -> "选集缓存"
            }
            primaryIcon.setImageResource(when {
                hasVideo -> R.drawable.ic_play_arrow
                value.running -> R.drawable.ic_pause
                else -> R.drawable.ic_download
            })
            primary.contentDescription = "${value.series.title}，${primaryLabel.text}"
            secondary.text = if (hasVideo && (value.running || pending))
                if (value.running) "暂停下载" else retryLabel else "选集缓存"
            secondary.contentDescription = "${value.series.title}，${secondary.text}"
            secondary.visibility = if (!hasVideo && !value.running && !pending) View.GONE else View.VISIBLE
            more.contentDescription = "管理《${value.series.title}》的离线缓存"
            cover.contentDescription = "${value.series.title}，选择离线分集播放"
        }
    }

    companion object {
        fun configureConcurrency(activity: MainActivity, onChanged: () -> Unit = {}) {
            val downloads = activity.offlineDownloads
            val values = (1..OfflineDownloads.MAX_CONCURRENCY).toList()
            PhoneDialog.choose(activity, "同时下载集数", values.map {
                when (it) {
                    1 -> "1 集 · 逐集下载"
                    OfflineDownloads.DEFAULT_CONCURRENCY -> "$it 集 · 推荐"
                    else -> "$it 集"
                }
            }, { index ->
                downloads.setConcurrency(values[index])
                onChanged()
            }, focusIndex = downloads.concurrency - 1).show()
        }

        private fun bytes(value: Long): String = if (value >= 1024L * 1024 * 1024) String.format(Locale.CHINA, "%.1f GB", value / (1024.0 * 1024 * 1024))
            else String.format(Locale.CHINA, "%.1f MB", value / (1024.0 * 1024))

        fun pick(activity: MainActivity, series: Series, detail: Detail? = null) {
            val downloads = activity.offlineDownloads
            if (!downloads.loaded) { PhoneUi.toast(activity, if (downloads.error.isBlank()) "离线记录正在加载，请稍后重试" else downloads.error); return }
            fun show(data: Detail) {
                if (activity.isFinishing || activity.isDestroyed) return
                if (data.episodes.isEmpty()) { PhoneUi.toast(activity, "暂时没有可下载的分集"); return }
                PhoneOfflineEpisodes.download(activity, data)
            }
            if (detail != null) { show(detail); return }
            val saved = downloads.items.firstOrNull { it.series.id == series.id }
            if (saved != null) { show(Detail(saved.series, saved.episodeIds)); return }
            val content = PortraitUi.row(activity).apply {
                setPadding(0, 0, 0, PortraitUi.dp(activity, 16))
                addView(ProgressBar(activity).apply {
                    indeterminateTintList = ColorStateList.valueOf(PortraitStyle.accent)
                }, PortraitUi.lp(PortraitUi.dp(activity, 24), PortraitUi.dp(activity, 24)).apply { rightMargin = PortraitUi.dp(activity, 12) })
                addView(PortraitUi.text(activity, "正在加载分集…", 15f, PortraitStyle.muted), PortraitUi.lp(0, -2).apply { weight = 1f })
            }
            val loading = PhoneDialog.sheet(activity, "选择缓存分集", series.title, content)
            loading.show()
            downloads.detail(series) { result ->
                if (loading.isShowing && !activity.isFinishing && !activity.isDestroyed) {
                    loading.dismiss()
                    result.onSuccess(::show).onFailure { PhoneUi.toast(activity, it.message ?: "分集加载失败，请重试") }
                }
            }
        }

        private fun chooseEpisode(activity: MainActivity, item: OfflineDownloads.Item) {
            if (item.downloaded.isNotEmpty()) PhoneOfflineEpisodes.play(activity, item)
        }
    }
}
