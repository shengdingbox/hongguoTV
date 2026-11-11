// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.hongguotv.core.RankBoard
import com.hongguotv.core.NewReleaseCalendar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** 手机统一的开心果 iOS 风格竖屏内容页。数据复用现有内容库。 */
class PortraitPages(
    private val activity: MainActivity,
    private val loadCover: (ImageView, String) -> Unit,
) {
    private fun dp(v: Int) = PortraitUi.dp(activity, v)
    private fun text(v: String, size: Float, color: Int = PortraitStyle.text) = PortraitUi.text(activity, v, size, color)
    private fun column() = PortraitUi.column(activity)
    private fun row() = PortraitUi.row(activity)
    private fun lp(w: Int = -1, h: Int = -2) = PortraitUi.lp(w, h)

    fun home(body: LinearLayout, scrollRoot: NestedScrollView? = null): (Boolean) -> Unit {
        body.addView(PortraitUi.pageTitle(activity, "推荐"), lp())
        val empty = PortraitUi.emptyHint(activity, "正在加载推荐…")
        body.addView(empty, lp())
        val grid = RecyclerView(activity)
        body.addView(grid, lp())
        val adapter = PortraitUi.PosterGrid(activity, loadCover, { activity.openDetailPhone(it) },
            { activity.loadMorePhone() }, scrollRoot)
        adapter.attach(grid)
        val error = text("", 14f, PortraitStyle.muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(24), dp(16), dp(24))
            background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 10)
            setOnClickListener { activity.retryRecommendationsPhone() }
        }
        body.addView(error, lp())
        return { loading ->
            val items = activity.recommendationItemsPhone.filterNot { it.id in activity.phoneLibrary.hidden() }
            val problem = activity.recommendationErrorPhone
            empty.text = if (loading) "正在加载推荐…" else "暂时没有推荐，下拉可重新加载"
            empty.visibility = if (items.isEmpty() && problem.isBlank()) View.VISIBLE else View.GONE
            grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            error.text = problem
            error.visibility = if (problem.isNotBlank()) View.VISIBLE else View.GONE
            adapter.submitItems(items, !loading && problem.isBlank() && activity.recommendationHasMorePhone)
        }
    }

    fun rank(body: LinearLayout, scrollRoot: NestedScrollView? = null): (Boolean) -> Unit {
        body.addView(PortraitUi.pageTitle(activity, "排行榜"), lp())
        val types = row().apply {
            background = PortraitStyle.shape(activity, PortraitStyle.raised, radius = 9)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        listOf(RankBoard.COMIC, RankBoard.REAL, RankBoard.AI).forEach { board ->
            val selected = activity.rankBoardPhone == board
            types.addView(text(board.label, 13f).apply {
                gravity = Gravity.CENTER
                background = PortraitStyle.selectable(activity, if (selected) Color.WHITE else Color.TRANSPARENT, radius = 7)
                isSelected = selected
                if (selected) setTypeface(null, Typeface.BOLD)
                setOnClickListener { activity.switchRankBoardPhone(board) }
            }, lp(0, dp(32)).apply { weight = 1f })
        }
        body.addView(types, lp().apply { leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(14) })
        val empty = PortraitUi.emptyHint(activity, "正在读取榜单…")
        body.addView(empty, lp())
        val grid = RecyclerView(activity)
        body.addView(grid, lp())
        val adapter = PortraitUi.PosterGrid(activity, loadCover, { activity.openDetailPhone(it) },
            { activity.loadMorePhone() }, scrollRoot, ranked = true)
        adapter.attach(grid)
        return { _ ->
            val items = activity.catalog
            empty.text = "这个榜单暂时是空的，下拉可重新加载"
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            adapter.submitItems(items, activity.hasMore)
        }
    }

    /** 服务端日历 + 按日期缓存的三列新剧列表，沿用统一详情、收藏和分页组件。 */
    fun newReleases(body: LinearLayout, scrollRoot: NestedScrollView? = null): (Boolean) -> Unit {
        body.addView(PortraitUi.pageTitle(activity, "新剧上线"), lp())
        val strip = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val dates = row().apply { setPadding(dp(16), dp(4), dp(16), dp(10)) }
        strip.addView(dates)
        body.addView(strip, lp())
        val loading = PortraitUi.emptyHint(activity, "正在加载新剧…")
        body.addView(loading, lp())
        val empty = PortraitUi.emptyState(activity, R.drawable.ic_phone_grid, "这一天没有新剧", "换个日期看看，或下拉刷新")
        body.addView(empty, lp())
        val grid = RecyclerView(activity)
        body.addView(grid, lp())
        val adapter = PortraitUi.PosterGrid(activity, loadCover, { activity.openDetailPhone(it) },
            { activity.loadMoreNewReleasesPhone() }, scrollRoot)
        adapter.attach(grid)
        val error = text("", 14f, PortraitStyle.muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(24), dp(16), dp(24))
            background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 10)
            setOnClickListener { activity.retryNewReleasesPhone() }
        }
        body.addView(error, lp())
        var shownDates = emptyList<String>()
        var shownSelected = ""
        fun updateDates() {
            val selectedDay = activity.newReleaseDatePhone
            val days = activity.newReleaseDatesPhone
            if (shownDates == days && shownSelected == selectedDay) return
            shownDates = days; shownSelected = selectedDay
            dates.removeAllViews()
            val format = SimpleDateFormat("yyyyMMdd", Locale.CHINA).apply {
                timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            }
            val today = NewReleaseCalendar.today()
            val yesterday = format.format(Calendar.getInstance(format.timeZone).apply { add(Calendar.DAY_OF_MONTH, -1) }.time)
            val tomorrow = format.format(Calendar.getInstance(format.timeZone).apply { add(Calendar.DAY_OF_MONTH, 1) }.time)
            var selectedChip: TextView? = null
            days.forEach { key ->
                val selected = key == selectedDay
                val label = when (key) {
                    today -> "今天"
                    yesterday -> "昨天"
                    tomorrow -> "明天"
                    else -> "${key.substring(4, 6).toInt()}月${key.substring(6, 8).toInt()}日"
                }
                val chip = text(label, 13f, if (selected) Color.WHITE else PortraitStyle.text).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(13), dp(8), dp(13), dp(8))
                    background = PortraitStyle.selectable(activity, if (selected) Color.BLACK else PortraitStyle.raised, radius = 20)
                    isSelected = selected
                    contentDescription = "$label，${if (selected) "已选择" else "查看新剧"}"
                    setOnClickListener { activity.chooseNewReleaseDayPhone(key) }
                }
                dates.addView(chip, lp(-2, -2).apply { rightMargin = dp(6) })
                if (selected) selectedChip = chip
            }
            strip.post {
                selectedChip?.let { strip.scrollTo((it.left - strip.width / 2 + it.width / 2).coerceAtLeast(0), 0) }
            }
        }
        return { _ ->
            updateDates()
            val items = activity.newReleaseItemsPhone.map { it.series }
            val busy = activity.newReleaseLoadingPhone
            val problem = activity.newReleaseErrorPhone
            loading.visibility = if (busy && items.isEmpty()) View.VISIBLE else View.GONE
            empty.visibility = if (!busy && problem.isBlank() && items.isEmpty()) View.VISIBLE else View.GONE
            grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            error.text = problem
            error.visibility = if (problem.isNotBlank()) View.VISIBLE else View.GONE
            adapter.submitItems(items, activity.newReleaseHasMorePhone && problem.isBlank())
        }
    }

    private fun section(body: LinearLayout, title: String) {
        body.addView(text(title, 20f).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(10))
        }, lp())
    }

    fun mine(body: LinearLayout, scrollRoot: NestedScrollView? = null) {
        val head = row()
        head.addView(PortraitUi.pageTitle(activity, "我的"), lp(0, -2).apply { weight = 1f })
        head.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_more)
            setColorFilter(PortraitStyle.text)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = PortraitStyle.selectable(activity, Color.TRANSPARENT)
            contentDescription = "设置"
            setOnClickListener { activity.showPhoneSettings() }
        }, lp(dp(44), dp(44)).apply { gravity = Gravity.BOTTOM; rightMargin = dp(10) })
        body.addView(head, lp())
        val history = activity.phoneLibrary.history().take(30)
        if (history.isNotEmpty()) {
            section(body, "最近观看")
            val strip = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
            val cards = row().apply { gravity = Gravity.TOP; setPadding(dp(16), 0, dp(16), 0) }
            strip.addView(cards)
            history.forEach { watch ->
                val card = PortraitUi.PosterCard(activity)
                card.title.text = watch.series.title
                card.title.textSize = 13f
                card.badge.text = if (watch.completed) "看完第 ${watch.episodeIndex + 1} 集" else "看到第 ${watch.episodeIndex + 1} 集"
                card.badge.textSize = 11f
                loadCover(card.image, watch.series.cover)
                if (!watch.completed && watch.duration > 0) {
                    card.poster.addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 1000
                        progress = (watch.position * 1000 / watch.duration).toInt().coerceIn(0, 1000)
                        progressTintList = ColorStateList.valueOf(PortraitStyle.accent)
                        progressBackgroundTintList = ColorStateList.valueOf(Color.argb(100, 255, 255, 255))
                    }, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM).apply {
                        leftMargin = dp(8); rightMargin = dp(8); bottomMargin = dp(7)
                    })
                }
                card.contentDescription = "${watch.series.title}，${card.badge.text}"
                card.setOnClickListener { activity.openDetailPhone(watch.series) }
                card.setOnLongClickListener {
                    val menu = PopupMenu(activity, card)
                    menu.menu.add(0, 0, 0, "继续播放")
                    menu.menu.add(0, 1, 1, "从最近观看中移除")
                    menu.setOnMenuItemClickListener { item ->
                        if (item.itemId == 0) activity.openDetailPhone(watch.series, resume = true)
                        else { activity.phoneLibrary.removeHistory(watch.series.id); activity.renderPhoneTab() }
                        true
                    }
                    menu.show()
                    true
                }
                cards.addView(card, lp(dp(96), -2).apply { rightMargin = dp(12) })
            }
            body.addView(strip, lp())
        }
        section(body, "收藏")
        favoritesGrid(body, scrollRoot)
    }

    fun favorites(body: LinearLayout, scrollRoot: NestedScrollView? = null) {
        body.addView(PortraitUi.pageTitle(activity, "收藏"), lp())
        favoritesGrid(body, scrollRoot)
    }

    private fun favoritesGrid(body: LinearLayout, scrollRoot: NestedScrollView?) {
        val items = activity.phoneLibrary.favorites()
        if (items.isEmpty()) {
            body.addView(PortraitUi.emptyState(activity, R.drawable.ic_phone_star, "还没有收藏", "在剧集页点「收藏」，或者长按海报，想看的就存下来。"), lp())
            return
        }
        val grid = RecyclerView(activity)
        body.addView(grid, lp())
        PortraitUi.PosterGrid(activity, loadCover, { activity.openDetailPhone(it) }, null, scrollRoot).apply {
            this.items = items
            attach(grid)
        }
    }

    fun search(body: LinearLayout, submit: (String) -> Unit, scrollRoot: NestedScrollView? = null) {
        val query = activity.phoneQueryText
        body.addView(PortraitUi.pageTitle(activity, if (query.isBlank()) "热搜" else "“$query”"), lp())
        val suggestions = column()
        val handler = Handler(Looper.getMainLooper())
        var pending: Runnable? = null
        val input = EditText(activity).apply {
            hint = "搜索短剧"
            textSize = 15f
            setTextColor(PortraitStyle.text)
            setHintTextColor(PortraitStyle.muted)
            isSingleLine = true
            background = null
            setPadding(0, dp(11), dp(12), dp(11))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(query)
        }
        fun search(value: String) {
            val clean = value.trim()
            if (clean.isBlank()) return
            if (clean.length > 80) {
                input.error = "搜索内容不能超过 80 个字"
                return
            }
            pending?.let { handler.removeCallbacks(it) }
            pending = null
            suggestions.removeAllViews()
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
            submit(clean)
        }
        val bar = row().apply { background = PortraitStyle.shape(activity, PortraitStyle.raised, radius = 12) }
        bar.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(PortraitStyle.muted)
            setPadding(dp(12), dp(12), dp(8), dp(12))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, lp(dp(40), dp(44)))
        bar.addView(input, lp(0, dp(44)).apply { weight = 1f })
        input.contentDescription = "搜索短剧"
        input.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                search(input.text.toString())
                true
            } else false
        }
        body.addView(bar, lp().apply { leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(12) })
        body.addView(suggestions, lp())
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pending?.let { handler.removeCallbacks(it) }
                pending = null
                suggestions.removeAllViews()
                val prefix = s?.toString()?.trim().orEmpty()
                if (prefix.isEmpty()) {
                    if (query.isNotBlank()) activity.clearPortraitSearch()
                    return
                }
                if (prefix == query) return
                val task = Runnable {
                    activity.loadPortraitSuggestions(prefix) { words ->
                        if (!input.isAttachedToWindow || input.text.toString().trim() != prefix) return@loadPortraitSuggestions
                        suggestions.removeAllViews()
                        val local = activity.phoneLibrary.searches().map { it.query }.filter { it.contains(prefix, ignoreCase = true) }
                        (words + local).distinct().take(8).forEach { word ->
                            suggestions.addView(text(word, 14f).apply {
                                setPadding(dp(16), dp(13), dp(16), dp(13))
                                background = PortraitStyle.selectable(activity, Color.TRANSPARENT)
                                setOnClickListener { search(word) }
                            }, lp())
                        }
                    }
                }
                pending = task
                handler.postDelayed(task, 300)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        input.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { pending?.let { handler.removeCallbacks(it) } }
        })
        if (query.isNotBlank()) {
            if (activity.catalog.isEmpty()) body.addView(PortraitUi.emptyHint(activity,
                if (activity.phoneSearchActive) "正在搜索…" else "没有找到“$query”相关的短剧，换个词试试"), lp())
            else {
                val grid = RecyclerView(activity)
                body.addView(grid, lp())
                PortraitUi.PosterGrid(activity, loadCover, { activity.openDetailPhone(it) },
                    { activity.loadMorePhone() }, scrollRoot).apply {
                    items = activity.catalog
                    attach(grid)
                    submitItems(activity.catalog, activity.hasMore)
                }
            }
        } else {
            body.addView(PortraitUi.emptyState(activity, R.drawable.ic_search, "暂无热搜数据", "输入名称，搜索想看的剧"), lp())
            val history = activity.phoneLibrary.searches().take(12)
            if (history.isNotEmpty()) {
                section(body, "搜索历史")
                history.forEach { recent ->
                    body.addView(text(recent.query, 14f).apply {
                        setPadding(dp(16), dp(13), dp(16), dp(13))
                        background = PortraitStyle.selectable(activity, Color.TRANSPARENT)
                        setOnClickListener { search(recent.query) }
                    }, lp())
                }
            }
        }
    }
}
