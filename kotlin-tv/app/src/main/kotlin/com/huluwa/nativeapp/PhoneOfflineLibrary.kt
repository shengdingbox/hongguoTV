// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.res.ColorStateList
import androidx.core.graphics.drawable.DrawableCompat
import android.app.AlertDialog
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
    // 列表外层已有缩进，卡片和提示区仅补足到页面统一的 16dp 边距。
    private val horizontalPadding = dp(16) - page.horizontalContentPadding
    // 标签栏已经统一留出 16dp 间距，这里不再重复叠加。
    val header = PortraitUi.column(activity).apply { setPadding(horizontalPadding, 0, horizontalPadding, 0) }
    private val expandedSeries = mutableSetOf<String>()
    private val warning = text("", 13f, PortraitStyle.secondaryText).apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setLineSpacing(dp(3).toFloat(), 1f)
        background = PortraitStyle.shape(activity, PortraitStyle.raised, radius = 14)
    }
    private val empty = PortraitUi.emptyState(activity, R.drawable.ic_download, "还没有离线缓存",
        "在剧集详情页点右上角缓存按钮\n选好分集，好剧随时看")
    private val loading = PortraitUi.row(activity).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(48), 0, dp(48))
        addView(ProgressBar(activity).apply {
            DrawableCompat.setTint(indeterminateDrawable, PortraitStyle.accent)
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
        header.addView(warning, lp())
        header.addView(loading, lp()); header.addView(empty, lp())
        val changed: () -> Unit = { if (page.isShown) activity.renderPhoneTab() }
        page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { downloads.observe(changed); changed() }
            override fun onViewDetachedFromWindow(view: View) { downloads.unobserve(changed) }
        })
    }

    fun update(visible: Boolean) {
        val items = if (visible) downloads.items else emptyList()
        header.visibility = if (visible && (downloads.error.isNotBlank() || !downloads.loaded || items.isEmpty()))
            View.VISIBLE else View.GONE
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
            background = PortraitStyle.shape(activity, PortraitStyle.raised, radius = 9)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val title = text("", 15.5f).apply {
            setTypeface(null, Typeface.BOLD)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        private val metadata = text("", 12f, PortraitStyle.muted)
        /**
         * 状态标签：v2 设计稿用「圆点 + 文字」代替色块胶囊。
         * 圆点和文字分别着色，失败态用 danger 色，其余用强调色 / 中性灰。
         */
        private val statusDot = View(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = lp(dp(6), dp(6)).apply { rightMargin = dp(5) }
        }
        private val status = text("", 12f, PortraitStyle.accent).apply {
            gravity = Gravity.CENTER_VERTICAL
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val statusRow = PortraitUi.row(activity).apply {
            addView(statusDot)
            addView(status, lp(-2, -2))
        }
        private val percent = text("", 11f, PortraitStyle.muted).apply { gravity = Gravity.END }
        private val total = progress()
        private val transferHost = PortraitUi.column(activity)
        private val transferViews = linkedMapOf<String, Triple<LinearLayout, TextView, ProgressBar>>()
        private val problem = text("", 12f, PortraitStyle.danger).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = PortraitStyle.shape(activity, PortraitStyle.dangerSurface, radius = 10)
            setOnClickListener { PhoneDialog.info(activity, "下载遇到问题", item.message).show() }
        }
        private lateinit var item: OfflineDownloads.Item
        private var boundSeries = ""
        private var boundCover = ""
        private val primaryLabel = text("", 13f).apply {
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        private val primaryIcon = ImageView(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = lp(dp(16), dp(16)).apply { rightMargin = dp(6) }
        }
        /**
         * 主操作：只有强调色文字与图标，没有任何底色色块。
         * 触控高度靠最小 44dp 保证，宽度由内容决定，不再等分整行。
         */
        private val primary = PortraitUi.row(activity).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(44)
            setPadding(dp(9), dp(10), dp(9), dp(10))
            background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 10)
            isFocusable = true
            isClickable = true
            addView(primaryIcon)
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
        private val secondaryLabel = text("", 13f, PortraitStyle.muted).apply { setTypeface(null, Typeface.NORMAL) }
        private val secondaryIcon = ImageView(activity).apply {
            setColorFilter(PortraitStyle.muted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = lp(dp(16), dp(16)).apply { rightMargin = dp(6) }
        }
        /** 次操作：中性灰文字，视觉上明确弱于主操作。 */
        private val secondary = PortraitUi.row(activity).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(44)
            setPadding(dp(9), dp(10), dp(9), dp(10))
            background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 10)
            isFocusable = true
            isClickable = true
            addView(secondaryIcon)
            addView(secondaryLabel, lp(-2, -2))
            setOnClickListener {
                if (item.downloaded.isNotEmpty() && (item.running || item.pending.isNotEmpty())) {
                    if (item.running) downloads.pause(item.series.id) else downloads.resume(item.series.id)
                } else pick(activity, item.series)
            }
        }
        private val toggleLabel = text("", 13f, PortraitStyle.muted).apply { setTypeface(null, Typeface.NORMAL) }
        private val toggleIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_keyboard_arrow_up)
            setColorFilter(PortraitStyle.muted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = lp(dp(16), dp(16)).apply { leftMargin = dp(2) }
        }
        /** 各集进度的展开 / 收起，独立成一个轻量按钮。 */
        private val toggle = PortraitUi.row(activity).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(44)
            setPadding(dp(9), dp(10), dp(9), dp(10))
            background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 10)
            isFocusable = true
            isClickable = true
            addView(toggleLabel, lp(-2, -2))
            addView(toggleIcon)
            setOnClickListener { toggleTransfers() }
        }
        /** 底部最右侧的删除入口：点击直接弹确认框，不再进二级菜单。 */
        private val remove = ImageView(activity).apply {
            setImageResource(R.drawable.ic_delete)
            setColorFilter(PortraitStyle.muted)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 22)
            isFocusable = true
            isClickable = true
            setOnClickListener { confirmRemove() }
        }

        init {
            itemView.layoutParams = RecyclerView.LayoutParams(-1, -2)
            (itemView as FrameLayout).apply {
                setPadding(horizontalPadding, 0, horizontalPadding, dp(10))
                addView(card, FrameLayout.LayoutParams(-1, -2))
            }
            card.apply {
                setPadding(0, 0, 0, 0)
                background = PortraitStyle.shape(activity, PortraitStyle.surface, radius = 18)
            }
            val heading = PortraitUi.row(activity).apply {
                gravity = Gravity.TOP
                setPadding(dp(13), dp(13), dp(13), dp(10))
            }
            heading.addView(cover, lp(dp(62), dp(83)).apply { rightMargin = dp(12) })
            heading.addView(PortraitUi.column(activity).apply {
                addView(title, lp())
                addView(metadata, lp().apply { topMargin = dp(4) })
                addView(statusRow, lp().apply { topMargin = dp(3) })
                // 进度条与百分比同一行，百分比固定在右侧不抖动。
                val line = PortraitUi.row(activity)
                line.addView(total, lp(0, dp(3)).apply { weight = 1f })
                line.addView(percent, lp(dp(38), -2).apply { leftMargin = dp(8) })
                addView(line, lp().apply { topMargin = dp(7) })
            }, lp(0, -2).apply { weight = 1f })
            card.addView(heading, lp())
            // 各集进度和错误提示属于内容区，放在分隔线之上，操作栏始终在卡片最底部。
            card.addView(transferHost, lp().apply {
                leftMargin = dp(13); rightMargin = dp(13); bottomMargin = dp(8)
            })
            card.addView(problem, lp().apply {
                leftMargin = dp(13); rightMargin = dp(13); bottomMargin = dp(10)
            })
            // 操作栏与内容之间用一条发丝分隔线分层，替代原来的实心按钮块。
            card.addView(View(activity).apply { setBackgroundColor(PortraitStyle.outline) },
                lp(-1, dp(1)))
            val foot = PortraitUi.row(activity).apply {
                addView(primary)
                addView(secondary)
                addView(toggle)
                addView(View(activity), lp(0, -2).apply { weight = 1f })
                addView(remove, lp(dp(40), dp(40)).apply { topMargin = dp(3); bottomMargin = dp(3) })
                setPadding(dp(7), 0, dp(3), 0)
                minimumHeight = dp(46)
            }
            card.addView(foot, lp())
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

        /** 删除确认：底栏删除入口和二级菜单共用同一段提示。 */
        private fun confirmRemove() {
            val current = item
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
            status.text = state
            // 「圆点 + 文字」状态：圆点单独着色，文字用强调色 / 中性灰，失败改 danger。
            val statusTone = when {
                failed -> PortraitStyle.danger
                pending -> PortraitStyle.muted
                else -> PortraitStyle.accent
            }
            status.setTextColor(statusTone)
            statusDot.background = PortraitStyle.shape(activity, statusTone, radius = 3)
            // 状态只做展示，展开各集进度的操作交给底栏的 toggle 按钮。
            toggle.visibility = if (value.transfers.isNotEmpty()) View.VISIBLE else View.GONE
            toggleLabel.text = if (expanded) "收起各集" else "各集进度"
            toggle.contentDescription = if (value.transfers.isNotEmpty())
                if (expanded) "收起各集进度" else "展开各集进度" else ""
            // 图标本身朝上：收起时朝上（可展开），展开时翻转朝下（可收起）。
            toggleIcon.rotation = if (expanded) 180f else 0f
            statusRow.contentDescription = state
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
                DrawableCompat.setTintList(bar.indeterminateDrawable, ColorStateList.valueOf(PortraitStyle.accent))
                bar.progress = if (transfer.totalBytes > 0) (transfer.bytes * 1000 / transfer.totalBytes).toInt().coerceIn(0, 1000) else 0
            }
            transferHost.visibility = if (active.isEmpty()) View.GONE else View.VISIBLE
            problem.text = value.message
            problem.visibility = if (failed) View.VISIBLE else View.GONE
            val retryLabel = if (failed) "重试" else "继续"
            val hasVideo = value.downloaded.isNotEmpty()
            // 措辞压到两字：缓存页里「缓存」二字是冗余的。
            // 已有缓存时主操作永远是「播放」，下载控制退到次操作，避免两个按钮同名。
            primaryLabel.text = when {
                hasVideo -> "播放"
                value.running -> "暂停"
                pending -> retryLabel
                else -> "选集缓存"
            }
            primaryIcon.setImageResource(when {
                hasVideo -> R.drawable.ic_play_arrow
                value.running -> R.drawable.ic_pause
                else -> R.drawable.ic_download
            })
            // 主操作只有强调色文字 + 图标，无底色；失败态主操作改为 danger 色。
            primaryLabel.setTextColor(if (failed) PortraitStyle.danger else PortraitStyle.accent)
            primaryIcon.setColorFilter(if (failed) PortraitStyle.danger else PortraitStyle.accent)
            primary.contentDescription = "${value.series.title}，${primaryLabel.text}"
            // 次操作：有缓存在下载时给「暂停 / 继续」，否则给「选集」。
            val secondaryPause = hasVideo && (value.running || pending)
            secondaryLabel.text = when {
                secondaryPause -> if (value.running) "暂停" else retryLabel
                else -> "选集"
            }
            secondaryIcon.setImageResource(when {
                !secondaryPause -> R.drawable.ic_episodes
                value.running -> R.drawable.ic_pause
                else -> R.drawable.ic_download
            })
            secondary.contentDescription = "${value.series.title}，${secondaryLabel.text}"
            // 无缓存、未在下载也没暂停时，选集已由主操作承担，次操作隐藏。
            secondary.visibility = if (!hasVideo && !value.running && !pending) View.GONE else View.VISIBLE
            remove.contentDescription = "删除《${value.series.title}》的离线缓存"
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
                    DrawableCompat.setTint(indeterminateDrawable, PortraitStyle.accent)
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
