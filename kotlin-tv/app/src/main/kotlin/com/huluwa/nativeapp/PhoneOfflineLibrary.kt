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
    val header = PortraitUi.column(activity).apply { setPadding(dp(16), dp(4), dp(16), dp(12)) }
    private val summaryTitle = text("", 17f).apply { setTypeface(null, Typeface.BOLD) }
    private val summarySubtitle = text("", 12f, PortraitStyle.muted)
    private val activeCount = text("", 12f, PortraitStyle.accent).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(7), dp(10), dp(7))
        background = PortraitStyle.shape(activity, PortraitStyle.raised, radius = 12)
    }
    private val summary = PortraitUi.row(activity).apply {
        setPadding(0, dp(8), 0, dp(14))
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_download)
            setColorFilter(PortraitStyle.accent)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = PortraitStyle.shape(activity, PortraitStyle.surface, radius = 14)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, lp(dp(44), dp(44)).apply { rightMargin = dp(12) })
        addView(PortraitUi.column(activity).apply {
            addView(summaryTitle, lp())
            addView(summarySubtitle, lp().apply { topMargin = dp(5) })
        }, lp(0, -2).apply { weight = 1f })
        addView(activeCount, lp(-2, -2).apply { leftMargin = dp(8) })
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
        header.addView(summary, lp()); header.addView(warning, lp())
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
        summary.visibility = if (items.isNotEmpty()) View.VISIBLE else View.GONE
        summaryTitle.text = "已缓存 ${items.sumOf { it.downloaded.size }} 集"
        summarySubtitle.text = "${items.size} 部剧集 · 占用 ${bytes(items.sumOf { it.size })}"
        val running = items.count { it.running }
        activeCount.text = "$running 部下载中"
        activeCount.visibility = if (running > 0) View.VISIBLE else View.GONE
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
            background = PortraitStyle.shape(activity, PortraitStyle.raised, radius = 10)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val title = text("", 17f).apply {
            setTypeface(null, Typeface.BOLD)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        private val metadata = text("", 12f, PortraitStyle.muted)
        private val status = text("", 11f, PortraitStyle.accent).apply {
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = PortraitStyle.shape(activity, PortraitStyle.raised, radius = 7)
        }
        private val totalLabel = text("", 12f, PortraitStyle.muted)
        private val percent = text("", 12f, PortraitStyle.accent)
        private val total = progress()
        private val current = text("", 12f, PortraitStyle.muted)
        private val episode = progress()
        private val problem = text("", 12f, PortraitStyle.secondaryText).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        private val actions = PortraitUi.row(activity)
        private lateinit var item: OfflineDownloads.Item
        private var boundSeries = ""
        private var boundCover = ""
        private fun button(label: String, primary: Boolean = false, action: () -> Unit) = text(label, 13f,
            if (primary) PortraitStyle.onAccent else PortraitStyle.accent).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            background = PortraitStyle.selectable(activity, if (primary) PortraitStyle.accent else PortraitStyle.raised, radius = 12)
            isFocusable = true
            setOnClickListener { action() }
            actions.addView(this, lp(0, -2).apply { weight = 1f; if (actions.childCount > 0) leftMargin = dp(8) })
        }
        private val play = button("播放缓存", primary = true) { chooseEpisode(activity, item) }
        private val toggle = button("继续下载") { if (item.running) downloads.pause(item.series.id) else downloads.resume(item.series.id) }
        private val select = button("选集缓存") { pick(activity, item.series) }

        init {
            itemView.layoutParams = RecyclerView.LayoutParams(-1, -2)
            (itemView as FrameLayout).apply {
                setPadding(dp(16), 0, dp(16), dp(12))
                addView(card, FrameLayout.LayoutParams(-1, -2))
            }
            card.apply {
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = PortraitStyle.shape(activity, PortraitStyle.surface, radius = 20)
            }
            val heading = PortraitUi.row(activity).apply { gravity = Gravity.TOP }
            heading.addView(cover, lp(dp(66), dp(88)).apply { rightMargin = dp(12) })
            heading.addView(PortraitUi.column(activity).apply {
                addView(title, lp())
                addView(metadata, lp().apply { topMargin = dp(6) })
                addView(status, lp(-2, -2).apply { topMargin = dp(8) })
            }, lp(0, -2).apply { weight = 1f })
            heading.addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_more)
                setColorFilter(PortraitStyle.muted)
                setPadding(dp(11), dp(11), dp(11), dp(11))
                background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 22)
                contentDescription = "管理离线缓存"
                isFocusable = true
                setOnClickListener { manage() }
            }, lp(dp(44), dp(44)).apply { leftMargin = dp(2) })
            card.addView(heading, lp())
            val totalHeading = PortraitUi.row(activity)
            totalHeading.addView(totalLabel, lp(0, -2).apply { weight = 1f })
            totalHeading.addView(percent, lp(-2, -2).apply { leftMargin = dp(8) })
            card.addView(totalHeading, lp().apply { topMargin = dp(14) })
            card.addView(total, lp(-1, dp(4)).apply { topMargin = dp(7) })
            card.addView(current, lp().apply { topMargin = dp(9) })
            card.addView(episode, lp(-1, dp(3)).apply { topMargin = dp(6) })
            card.addView(problem, lp().apply { topMargin = dp(8) })
            card.addView(actions, lp().apply { topMargin = dp(14) })
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

        private fun manage() {
            val current = item
            PhoneDialog.choose(activity, "管理离线缓存", listOf("选择缓存分集", "删除离线缓存"), { choice ->
                if (choice == 0) pick(activity, current.series)
                else {
                    lateinit var confirmation: AlertDialog
                    val buttons = PortraitUi.column(activity)
                    buttons.addView(PhoneDialog.button(activity, "删除缓存", destructive = true) {
                        confirmation.dismiss()
                        downloads.remove(current.series.id)
                    }, lp())
                    buttons.addView(PhoneDialog.button(activity, "取消") { confirmation.dismiss() }, lp().apply { topMargin = dp(10) })
                    confirmation = PhoneDialog.sheet(activity, "删除离线缓存",
                        "将删除《${current.series.title}》的全部缓存和未完成文件，已缓存内容占用 ${bytes(current.size)}。", buttons)
                    confirmation.show()
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
            metadata.text = "已缓存 ${value.downloaded.size} 集 · ${bytes(value.size)}"
            val failed = !value.running && value.pending.isNotEmpty() && value.message.isNotBlank() && !value.message.startsWith("已暂停")
            status.text = when {
                value.running && value.current == 0 -> "排队中"
                value.running -> "正在缓存"
                failed -> "下载遇到问题"
                value.pending.isNotEmpty() -> "已暂停"
                else -> "缓存完成"
            }
            status.setTextColor(if (failed) PortraitStyle.secondaryText else PortraitStyle.accent)
            totalLabel.text = "总进度 ${value.downloaded.size} / ${value.selected.size} 集"
            percent.text = "${value.progress / 10}%"
            total.progress = value.progress
            val downloading = value.running && value.current > 0
            current.visibility = if (downloading) View.VISIBLE else View.GONE
            episode.visibility = current.visibility
            current.text = "第 ${value.current} 集 · ${bytes(value.bytes)} / ${bytes(value.totalBytes)}"
            episode.progress = if (value.totalBytes > 0) (value.bytes * 1000 / value.totalBytes).toInt().coerceIn(0, 1000) else 0
            problem.text = value.message
            problem.visibility = if (failed) View.VISIBLE else View.GONE
            play.isEnabled = value.downloaded.isNotEmpty(); play.alpha = if (play.isEnabled) 1f else .4f
            toggle.text = when { value.running -> "暂停下载"; failed -> "重试下载"; else -> "继续下载" }
            toggle.visibility = if (value.running || value.pending.isNotEmpty()) View.VISIBLE else View.GONE
            cover.contentDescription = "${value.series.title}，选择离线分集播放"
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
