// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
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
import com.hongguotv.core.HotSearchTopic
import com.hongguotv.core.NewReleaseCalendar
import com.hongguotv.core.Series
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** 手机统一的第三方 iOS 客户端风格竖屏内容页。数据复用现有内容库。 */
class PortraitPages(
    private val activity: MainActivity,
    private val loadCover: (ImageView, String) -> Unit,
) {
    private fun dp(v: Int) = PortraitUi.dp(activity, v)
    private fun text(v: String, size: Float, color: Int = PortraitStyle.text) = PortraitUi.text(activity, v, size, color)
    private fun column() = PortraitUi.column(activity)
    private fun row() = PortraitUi.row(activity)
    private fun lp(w: Int = -1, h: Int = -2) = PortraitUi.lp(w, h)

    // 扣除列表外层缩进，使标题与排行榜、新剧的页头保持相同边距。
    private fun listPageTitle(page: PortraitListPage, label: String) = PortraitUi.pageTitle(activity, label).apply {
        setPadding(paddingLeft - page.horizontalContentPadding, paddingTop, paddingRight - page.horizontalContentPadding, paddingBottom)
    }

    fun home(page: PortraitListPage): (Boolean) -> Unit {
        val body=column(); page.addBlock(body)
        val header = row()
        header.addView(listPageTitle(page, "推荐"), lp(0, -2).apply { weight = 1f })
        header.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(PortraitStyle.text)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 24)
            contentDescription = "搜索"
            isFocusable = true
            setOnClickListener { activity.switchPhoneTab("search") }
        }, lp(dp(48), dp(48)).apply { rightMargin = dp(4) })
        body.addView(header, lp())
        val empty = PortraitUi.emptyHint(activity, "正在加载推荐…")
        body.addView(empty, lp())
        val adapter = PortraitUi.PosterGrid(activity, loadCover, { series, cover -> activity.openDetailPhone(series, cover = cover) },
            { activity.loadMorePhone() }, loadAheadScreens = 2)
        page.addGrid(adapter)
        val error = text("", 14f, PortraitStyle.muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(24), dp(16), dp(24))
            background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 10)
            setOnClickListener { activity.retryRecommendationsPhone() }
        }
        page.addBlock(error)
        return { loading ->
            val allItems = activity.recommendationItemsPhone
            val items = allItems.filterNot { it.id in activity.phoneLibrary.hidden() }
            val problem = activity.recommendationErrorPhone
            val allHidden = allItems.isNotEmpty() && items.isEmpty()
            empty.text = when {
                loading -> "正在加载推荐…"
                allHidden && activity.recommendationHasMorePhone -> "本批推荐已被隐藏，点此加载更多"
                allHidden -> "推荐已被隐藏，可在设置中恢复隐藏推荐"
                else -> "暂时没有推荐，点此重新加载"
            }
            empty.isClickable = !loading
            empty.setOnClickListener {
                if (!loading) {
                    if (allHidden && activity.recommendationHasMorePhone) activity.loadMorePhone()
                    else activity.retryRecommendationsPhone()
                }
            }
            empty.visibility = if (items.isEmpty() && problem.isBlank()) View.VISIBLE else View.GONE
            error.text = problem
            error.visibility = if (problem.isNotBlank()) View.VISIBLE else View.GONE
            adapter.submitItems(items, !loading && problem.isBlank() && activity.recommendationHasMorePhone)
        }
    }

    fun rankPager(refresh: () -> Unit): PortraitFeedPager {
        lateinit var feed: PortraitFeedPager
        val tabs=column()
        // 两行保持各自的滚动容器，切换子榜只更新选中态，不重建类型行。
        class RankTabs(private val segmented: Boolean,private val choose: (String)->Unit): HorizontalScrollView(activity) {
            private val line=row().apply { setPadding(dp(16),dp(3),dp(16),dp(10)) }
            private var values=emptyList<Pair<String,String>>()
            private val buttons=linkedMapOf<String,TextView>()
            private var selected=""
            private var pendingCenter: ViewTreeObserver.OnPreDrawListener?=null
            private var pendingObserver: ViewTreeObserver?=null

            init {
                isHorizontalScrollBarEnabled=false
                addView(line)
                addOnLayoutChangeListener { _,left,_,right,_,oldLeft,_,oldRight,_ ->
                    if(right-left!=oldRight-oldLeft) centerAfterLayout(false)
                }
            }

            private fun cancelCenter() {
                pendingCenter?.let { listener ->
                    val observer=pendingObserver?.takeIf { it.isAlive } ?: viewTreeObserver
                    if(observer.isAlive) observer.removeOnPreDrawListener(listener)
                }
                pendingCenter=null; pendingObserver=null
            }

            private fun centerAfterLayout(animate: Boolean) {
                cancelCenter()
                val observer=viewTreeObserver
                val listener=object: ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if(pendingCenter!==this) return true
                        cancelCenter()
                        val button=buttons[selected] ?: return true
                        val viewport=width-paddingLeft-paddingRight
                        if(viewport<=0 || button.width<=0) return true
                        val maxScroll=(line.width-viewport).coerceAtLeast(0)
                        val target=(line.left+button.left+button.width/2-paddingLeft-viewport/2).coerceIn(0,maxScroll)
                        if(animate) smoothScrollTo(target,0) else scrollTo(target,0)
                        return true
                    }
                }
                pendingCenter=listener; pendingObserver=observer
                observer.addOnPreDrawListener(listener)
                invalidate()
            }

            fun update(nextValues: List<Pair<String,String>>,nextSelected: String) {
                val rebuilt=values!=nextValues
                if(!rebuilt && selected==nextSelected) return
                val animate=!rebuilt && selected.isNotEmpty() && isShown
                if(rebuilt) {
                    values=nextValues
                    line.removeAllViews(); buttons.clear()
                    values.forEach { (key,label) ->
                        val button=text(label,13f).apply {
                            gravity=Gravity.CENTER
                            setPadding(dp(14),dp(8),dp(14),dp(8))
                            setOnClickListener { choose(key) }
                        }
                        buttons[key]=button
                        line.addView(button,lp(-2,-2).apply { rightMargin=dp(6) })
                    }
                }
                selected=nextSelected
                values.forEach { (key,label) -> buttons[key]?.apply {
                    val on=key==selected
                    setTextColor(if(on) PortraitStyle.onChipSelected else PortraitStyle.text)
                    background=PortraitStyle.selectable(activity,if(on) PortraitStyle.chipSelected else PortraitStyle.raised,radius=if(segmented) 9 else 20)
                    setTypeface(null,if(on) Typeface.BOLD else Typeface.NORMAL)
                    isSelected=on
                    contentDescription="$label，${if(on) "已选择" else "查看榜单"}"
                } }
                centerAfterLayout(animate)
            }

            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                centerAfterLayout(false)
            }

            override fun onDetachedFromWindow() {
                cancelCenter()
                super.onDetachedFromWindow()
            }
        }
        val contentTabs=RankTabs(true,activity::switchRankContentPhone)
        val boardTabs=RankTabs(false) { feed.selectPage(it) }
        tabs.addView(contentTabs,lp())
        tabs.addView(boardTabs,lp())
        fun updateTabs(key: String) {
            val contents=activity.mobileRankContentsPhone
            contentTabs.visibility=if(contents.size>1) View.VISIBLE else View.GONE
            contentTabs.update(contents.map { it.id to it.name },activity.phoneRankContentId)
            val boards=activity.mobileRankBoardsPhone
            boardTabs.visibility=if(boards.isNotEmpty()) View.VISIBLE else View.GONE
            boardTabs.update(boards.map { it.id to it.name },key)
        }
        feed=PortraitFeedPager(activity,"排行榜",tabs,
            keys={ activity.mobileRankBoardsPhone.map { it.id }.ifEmpty { listOf("_default") } },
            selected={ activity.mobileRankSelectedKeyPhone },
            select=activity::switchRankBoardPhone,
            updateTabs=::updateTabs,
            loading={ activity.rankLoadingPhone },refresh=refresh,
        ) { key,page ->
            val body=column(); page.addBlock(body)
            val empty=PortraitUi.emptyHint(activity,"正在读取榜单…")
            body.addView(empty,lp())
            val adapter=PortraitUi.PosterGrid(activity,loadCover,{ series, cover -> activity.openDetailPhone(series, cover = cover) },
                { if(feed.isCurrent(key)) activity.loadMorePhone() },ranked=true,loadAheadScreens=1)
            page.addGrid(adapter)
            val error=text("",14f,PortraitStyle.muted).apply {
                gravity=Gravity.CENTER; setPadding(dp(16),dp(24),dp(16),dp(24))
                setOnClickListener { if(feed.isCurrent(key)) activity.retryRankPhone() }
            }
            page.addBlock(error)
            val update: ()->Unit={
                val page=activity.rankPagePhone(key)
                val items=page?.items.orEmpty()
                val current=key==activity.mobileRankSelectedKeyPhone
                val busy=current && activity.rankLoadingPhone
                val problem=activity.rankErrorPhone(key)
                empty.text=if(busy) "正在读取榜单…" else "这个榜单暂时是空的，下拉可重新加载"
                empty.visibility=if(items.isEmpty() && problem.isBlank()) View.VISIBLE else View.GONE
                error.text=problem; error.visibility=if(problem.isBlank()) View.GONE else View.VISIBLE
                adapter.submitItems(items,page?.cursor!=null && !busy && problem.isBlank())
            }
            update
        }
        return feed
    }

    /** 每个日期持有独立列表和滚动位置，Tab 与原生分页双向联动。 */
    fun newReleasesPager(refresh: () -> Unit): PortraitFeedPager {
        lateinit var feed: PortraitFeedPager
        val strip = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val dates = row().apply { setPadding(dp(16), dp(4), dp(16), dp(10)) }
        strip.addView(dates)
        var shownDates = emptyList<String>()
        var shownSelected = ""
        fun updateDates(selectedDay: String) {
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
                val chip = text(label, 13f, if (selected) PortraitStyle.onChipSelected else PortraitStyle.text).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(13), dp(8), dp(13), dp(8))
                    background = PortraitStyle.selectable(activity, if (selected) PortraitStyle.chipSelected else PortraitStyle.raised, radius = 20)
                    isSelected = selected
                    contentDescription = "$label，${if (selected) "已选择" else "查看新剧"}"
                    setOnClickListener { feed.selectPage(key) }
                }
                dates.addView(chip, lp(-2, -2).apply { rightMargin = dp(6) })
                if (selected) selectedChip = chip
            }
            strip.post {
                selectedChip?.let { strip.smoothScrollTo((it.left - strip.width / 2 + it.width / 2).coerceAtLeast(0), 0) }
            }
        }
        feed = PortraitFeedPager(activity, "新剧上线", strip,
            keys = { activity.newReleaseDatesPhone },
            selected = { activity.newReleaseDatePhone },
            select = { activity.chooseNewReleaseDayPhone(it) },
            updateTabs = ::updateDates,
            loading = { activity.newReleaseLoadingPhone },
            refresh = refresh,
        ) { key, page ->
            val body=column(); page.addBlock(body)
            val loading = PortraitUi.emptyHint(activity, "正在加载新剧…")
            body.addView(loading, lp())
            val empty = PortraitUi.emptyState(activity, R.drawable.ic_phone_grid, "这一天没有新剧", "换个日期看看，或下拉刷新")
            body.addView(empty, lp())
            val adapter = PortraitUi.PosterGrid(activity, loadCover, { series, cover -> activity.openDetailPhone(series, cover = cover) },
                { if (feed.isCurrent(key)) activity.loadMoreNewReleasesPhone() })
            page.addGrid(adapter)
            val error = text("", 14f, PortraitStyle.muted).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(24), dp(16), dp(24))
                background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 10)
                setOnClickListener { if (feed.isCurrent(key)) activity.retryNewReleasesPhone() }
            }
            page.addBlock(error)
            val update: () -> Unit = {
                val page = activity.newReleasePagePhone(key)
                val items = page?.items.orEmpty().map { it.series }
                val selected = activity.newReleaseDatePhone == key
                val busy = if (selected) activity.newReleaseLoadingPhone else page == null
                val problem = if (selected) activity.newReleaseErrorPhone else ""
                loading.visibility = if (busy && items.isEmpty()) View.VISIBLE else View.GONE
                empty.visibility = if (!busy && problem.isBlank() && items.isEmpty()) View.VISIBLE else View.GONE
                error.text = problem
                error.visibility = if (problem.isNotBlank()) View.VISIBLE else View.GONE
                adapter.submitItems(items, page?.cursor != null && problem.isBlank())
            }
            update
        }
        return feed
    }

    private fun section(body: LinearLayout, title: String, horizontalPadding: Int) {
        body.addView(text(title, 20f).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(horizontalPadding, dp(16), horizontalPadding, dp(10))
        }, lp())
    }

    /** 最近观看按剧集复用卡片，进度和收藏变化只更新各自的内容。 */
    fun mine(page: PortraitListPage): (Boolean) -> Unit {
        val body=column(); page.addBlock(body)
        val head = row()
        val title = listPageTitle(page, "我的")
        val startPadding = title.paddingLeft
        head.addView(title, lp(0, -2).apply { weight = 1f })
        head.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_more)
            setColorFilter(PortraitStyle.text)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = PortraitStyle.selectable(activity, Color.TRANSPARENT)
            contentDescription = "设置"
            setOnClickListener { activity.showPhoneSettings() }
        }, lp(dp(44), dp(44)).apply { gravity = Gravity.BOTTOM; rightMargin = dp(10) })
        body.addView(head, lp())
        val recent = column()
        val recentHeading = row().apply { setPadding(startPadding, 0, dp(8), 0) }
        recentHeading.addView(text("最近观看", 20f).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(10))
        }, lp(0, -2).apply { weight = 1f })
        recentHeading.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_delete)
            setColorFilter(PortraitStyle.muted)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 22)
            contentDescription = "删除全部播放记录"
            isFocusable = true
            setOnClickListener {
                lateinit var confirmation: AlertDialog
                val buttons = column()
                buttons.addView(PhoneDialog.button(activity, "删除", destructive = true) {
                    confirmation.dismiss()
                    activity.phoneLibrary.clearHistory()
                    activity.renderPhoneTab()
                }, lp())
                buttons.addView(PhoneDialog.button(activity, "取消") { confirmation.dismiss() }, lp().apply { topMargin = dp(10) })
                confirmation = PhoneDialog.sheet(activity, "删除播放记录", "是否删除全部播放记录？删除后无法恢复。", buttons)
                confirmation.show()
            }
        }, lp(dp(44), dp(44)))
        recent.addView(recentHeading, lp())
        val strip = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val cards = row().apply { gravity = Gravity.TOP; setPadding(startPadding, 0, dp(16), 0) }
        strip.addView(cards)
        strip.viewTreeObserver.addOnScrollChangedListener { activity.refreshPhoneCovers() }
        recent.addView(strip, lp())
        body.addView(recent, lp())
        val retainedCards = mutableMapOf<String, RecentWatchCard>()

        val tabHost = column().apply { setPadding(startPadding, dp(10), dp(16), dp(8)) }
        // 底板与选中块向内收紧，点击区域仍保留至少 44dp，并随字体大小伸展。
        val tabs = FrameLayout(activity).apply {
            background = InsetDrawable(PortraitStyle.shape(activity, PortraitStyle.raised, radius = 12), 0, dp(4), 0, dp(4))
            setPadding(0, 0, 0, 0)
        }
        val tabRow = row().apply { setPadding(dp(3), 0, dp(3), 0) }
        val tabButtons = listOf("收藏", "离线缓存").mapIndexed { index, label ->
            text(label, 14f).apply {
                gravity = Gravity.CENTER
                minHeight = dp(44)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                isFocusable = true
                setOnClickListener { activity.selectMineOfflineTab(index == 1) }
                tabRow.addView(this, lp(-2, -2))
            }
        }
        tabs.addView(tabRow, FrameLayout.LayoutParams(-2, -2))
        tabHost.addView(tabs, lp(-2, -2))
        body.addView(tabHost, lp())
        val emptyFavorites = PortraitUi.emptyState(activity, R.drawable.ic_phone_star, "还没有收藏", "在剧集页点「收藏」，或者长按海报，想看的就存下来。")
        body.addView(emptyFavorites, lp())
        val favoritesAdapter = PortraitUi.PosterGrid(activity, loadCover, { series, cover -> activity.openDetailPhone(series, cover = cover) }, null)
        page.addGrid(favoritesAdapter)
        val offline = PhoneOfflineLibrary(activity, page, loadCover)
        page.addBlock(offline.header)
        page.addRows(offline.adapter)
        var shownHistory: List<WatchProgress>? = null
        var shownFavorites: List<Series>? = null
        return { _ ->
            val history = activity.phoneLibrary.history().take(30)
            if (shownHistory != history) {
                val ids = history.map { it.series.id }.toSet()
                retainedCards.keys.filter { it !in ids }.forEach { id ->
                    retainedCards.remove(id)?.let { entry ->
                        activity.recyclePhoneCover(entry.card.image)
                        cards.removeView(entry.card)
                    }
                }
                history.forEachIndexed { index, watch ->
                    val entry = retainedCards.getOrPut(watch.series.id) { recentWatchCard(watch) }
                    if (cards.indexOfChild(entry.card) != index) {
                        cards.removeView(entry.card)
                        cards.addView(entry.card, index, lp(dp(96), -2).apply { rightMargin = dp(12) })
                    }
                    updateRecentWatchCard(entry, watch)
                }
                recent.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
                shownHistory = history
            }
            val offlineSelected = activity.mineOfflineSelected
            tabButtons.forEachIndexed { index, button ->
                val selected = (index == 1) == offlineSelected
                button.isSelected = selected
                if (button.tag != selected) {
                    button.tag = selected
                    button.setTextColor(if (selected) PortraitStyle.onChipSelected else PortraitStyle.secondaryText)
                    button.background = InsetDrawable(PortraitStyle.selectable(activity,
                        if (selected) PortraitStyle.chipSelected else Color.TRANSPARENT, radius = 9), 0, dp(6), 0, dp(6))
                    button.setPadding(dp(14), dp(8), dp(14), dp(8))
                    button.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                }
                button.contentDescription = "${button.text}，${if (selected) "已选中" else "未选中"}"
            }
            offline.update(offlineSelected)
            val favorites = if (offlineSelected) emptyList() else activity.phoneLibrary.favorites()
            emptyFavorites.visibility = if (!offlineSelected && favorites.isEmpty()) View.VISIBLE else View.GONE
            if (shownFavorites != favorites) {
                favoritesAdapter.submitItems(favorites, false)
                shownFavorites = favorites
            }
        }
    }

    private class RecentWatchCard(val card: PortraitUi.PosterCard, val progress: ProgressBar, var watch: WatchProgress)

    private fun recentWatchCard(watch: WatchProgress): RecentWatchCard {
        val card = PortraitUi.PosterCard(activity)
        card.title.textSize = 13f
        card.badge.textSize = 11f
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(PortraitStyle.accent)
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(100, 255, 255, 255))
            visibility = View.GONE
        }
        card.poster.addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM).apply {
            leftMargin = dp(8); rightMargin = dp(8); bottomMargin = dp(7)
        })
        val entry = RecentWatchCard(card, progress, watch)
        card.setOnClickListener { activity.openDetailPhone(entry.watch.series) }
        card.setOnLongClickListener {
            val current = entry.watch
            val menu = PopupMenu(PortraitStyle.popupContext(activity), card)
            menu.menu.add(0, 0, 0, "继续播放")
            menu.menu.add(0, 1, 1, "从最近观看中移除")
            menu.setOnMenuItemClickListener { item ->
                if (item.itemId == 0) activity.openDetailPhone(current.series, resume = true)
                else { activity.phoneLibrary.removeHistory(current.series.id); activity.renderPhoneTab() }
                true
            }
            menu.show()
            true
        }
        return entry
    }

    private fun updateRecentWatchCard(entry: RecentWatchCard, watch: WatchProgress) {
        val previous = entry.watch
        entry.watch = watch
        val card = entry.card
        card.title.text = watch.series.title
        card.badge.text = if (watch.completed) "看完第 ${watch.episodeIndex + 1} 集" else "看到第 ${watch.episodeIndex + 1} 集"
        card.contentDescription = "${watch.series.title}，${card.badge.text}"
        if (card.image.drawable == null) loadCover(card.image, watch.series.cover)
        else if (previous.series.cover != watch.series.cover) activity.updatePhoneCover(card.image, watch.series.cover)
        entry.progress.visibility = if (!watch.completed && watch.duration > 0) View.VISIBLE else View.GONE
        entry.progress.progress = if (watch.duration > 0) (watch.position * 1000 / watch.duration).toInt().coerceIn(0, 1000) else 0
    }

    fun favorites(page: PortraitListPage): (Boolean)->Unit {
        val body=column(); page.addBlock(body)
        body.addView(PortraitUi.pageTitle(activity, "收藏"), lp())
        val empty=PortraitUi.emptyState(activity, R.drawable.ic_phone_star, "还没有收藏", "在剧集页点「收藏」，或者长按海报，想看的就存下来。")
        body.addView(empty,lp())
        val adapter=PortraitUi.PosterGrid(activity,loadCover,{ series, cover -> activity.openDetailPhone(series, cover = cover) },null)
        page.addGrid(adapter)
        return { _ ->
            val items=activity.phoneLibrary.favorites()
            empty.visibility=if(items.isEmpty()) View.VISIBLE else View.GONE
            adapter.submitItems(items,false)
        }
    }

    fun search(page: PortraitListPage, submit: (String) -> Unit): (Boolean) -> Unit {
        val body=column(); page.addBlock(body)
        var query = activity.phoneQueryText
        val title = listPageTitle(page, if (query.isBlank()) "热搜" else "“$query”")
        val horizontalPadding = title.paddingLeft
        body.addView(title, lp())
        val suggestions = column()
        val handler = Handler(Looper.getMainLooper())
        var pending: Runnable? = null
        var updatingInput = false
        val input = EditText(activity).apply {
            hint = "搜索${activity.phoneLibrary.contentType.label}"
            textSize = 15f
            setTextColor(PortraitStyle.text)
            setHintTextColor(PortraitStyle.muted)
            isSingleLine = true
            background = null
            setPadding(0, dp(11), dp(12), dp(11))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(activity.phoneSearchDraft)
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
            activity.cancelSearchSuggestions()
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
        val clear = ImageView(activity).apply {
            setImageResource(R.drawable.ic_search_clear)
            setColorFilter(PortraitStyle.muted)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 22)
            contentDescription = "清空搜索内容"
            isFocusable = true
            visibility = if (input.text.isEmpty()) View.INVISIBLE else View.VISIBLE
            setOnClickListener {
                input.text.clear()
                input.requestFocus()
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        bar.addView(clear, lp(dp(44), dp(44)))
        input.contentDescription = input.hint
        input.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                search(input.text.toString())
                true
            } else false
        }
        body.addView(bar, lp().apply { leftMargin = horizontalPadding; rightMargin = horizontalPadding; bottomMargin = dp(12) })
        body.addView(suggestions, lp())
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clear.visibility = if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
                if (updatingInput) return
                pending?.let { handler.removeCallbacks(it) }
                pending = null
                activity.cancelSearchSuggestions()
                suggestions.removeAllViews()
                activity.phoneSearchDraft=s?.toString().orEmpty()
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
                                setPadding(horizontalPadding, dp(13), horizontalPadding, dp(13))
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
            override fun onViewDetachedFromWindow(v: View) { pending?.let { handler.removeCallbacks(it) }; activity.cancelSearchSuggestions() }
        })
        val results=column()
        body.addView(results,lp())
        val errorBody=column()
        results.addView(errorBody,lp())
        val empty=PortraitUi.emptyHint(activity,"").apply {
            setPadding(horizontalPadding, paddingTop, horizontalPadding, paddingBottom)
        }
        results.addView(empty,lp())
        val adapter=PortraitUi.PosterGrid(activity,loadCover,{ series, cover -> activity.openDetailPhone(series, cover = cover) },
            { activity.loadMorePhone() })
        page.addGrid(adapter)
        val hotBody=column()
        page.addBlock(hotBody)
        val updateHotSearch=renderHotSearch(page,hotBody,horizontalPadding,::search)
        val historyBody=column()
        page.addBlock(historyBody)
        var shownError=""
        var shownHistory: List<String>?=null
        // 搜索与热搜共用原输入框，清空时只切换下方内容，避免焦点与输入法连接丢失。
        return { _ ->
            query=activity.phoneQueryText
            title.text=if(query.isBlank()) "热搜" else "“$query”"
            val draft=activity.phoneSearchDraft
            if(input.text.toString()!=draft) {
                updatingInput=true
                try {
                    input.setText(draft)
                    input.setSelection(input.text.length)
                } finally {
                    updatingInput=false
                }
            }
            val searching=query.isNotBlank()
            results.visibility=if(searching) View.VISIBLE else View.GONE
            val items=if(searching) activity.catalog else emptyList()
            val error=if(searching) activity.phoneSearchErrorPhone else ""
            if(shownError!=error) {
                shownError=error
                errorBody.removeAllViews()
                if(error.isNotBlank()) errorBody.addView(PortraitUi.messageBlock(activity,error) { submit(query) }.apply {
                    setPadding(horizontalPadding, paddingTop, horizontalPadding, paddingBottom)
                },lp())
            }
            empty.text=if(activity.phoneSearchActive) "正在搜索…"
                else "没有找到“$query”相关的${activity.phoneLibrary.contentType.label}，换个词试试"
            empty.visibility=if(items.isEmpty() && error.isBlank()) View.VISIBLE else View.GONE
            adapter.submitItems(items,searching && activity.hasMore && !activity.phoneSearchActive && error.isBlank())
            updateHotSearch(!searching)
            historyBody.visibility=if(searching) View.GONE else View.VISIBLE
            val history=if(searching) emptyList() else activity.phoneLibrary.searches().take(12).map { it.query }
            if(shownHistory!=history) {
                shownHistory=history
                historyBody.removeAllViews()
                if(history.isNotEmpty()) {
                    section(historyBody,"搜索历史",horizontalPadding)
                    history.forEach { recent ->
                        historyBody.addView(text(recent,14f).apply {
                            setPadding(horizontalPadding,dp(13),horizontalPadding,dp(13))
                            background=PortraitStyle.selectable(activity,Color.TRANSPARENT)
                            setOnClickListener { search(recent) }
                        },lp())
                    }
                }
            }
        }
    }

    /** 缓存首帧、联网刷新及切榜共用网格，只提交剧集差异。 */
    private fun renderHotSearch(page: PortraitListPage,body: LinearLayout,horizontalPadding: Int,search: (String)->Unit): (Boolean)->Unit {
        val loading=PortraitUi.emptyHint(activity,"正在加载热搜…").apply {
            setPadding(horizontalPadding, paddingTop, horizontalPadding, paddingBottom)
        }
        body.addView(loading,lp())
        val errorBody=column()
        body.addView(errorBody,lp())
        fun grid(ranked: Boolean): PortraitUi.PosterGrid {
            val adapter=PortraitUi.PosterGrid(activity,loadCover,{ series, cover -> activity.openDetailPhone(series, cover = cover) },null,ranked=ranked)
            page.addGrid(adapter)
            return adapter
        }
        val guesses=column()
        section(guesses,"猜你想搜",horizontalPadding)
        page.addBlock(guesses)
        val guessesAdapter=grid(false)
        val boards=column()
        val strip=HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled=false }
        val choices=row().apply { setPadding(horizontalPadding,dp(12),horizontalPadding,dp(8)) }
        strip.addView(choices)
        boards.addView(strip,lp())
        page.addBlock(boards)
        val boardAdapter=grid(true)
        val topics=column()
        page.addBlock(topics)
        val chips=linkedMapOf<String,TextView>()
        var shownBoards=emptyList<String>()
        var shownSelected=""
        var shownTopics: List<HotSearchTopic>?=null
        var shownError=""
        return { visible ->
            body.visibility=if(visible) View.VISIBLE else View.GONE
            topics.visibility=if(visible) View.VISIBLE else View.GONE
            val hot=activity.hotSearchPhone.takeIf { visible }
            loading.visibility=if(visible && activity.hotSearchLoadingPhone && hot==null) View.VISIBLE else View.GONE
            val error=if(visible) activity.hotSearchErrorPhone else ""
            if(shownError!=error) {
                shownError=error
                errorBody.removeAllViews()
                if(error.isNotBlank()) errorBody.addView(PortraitUi.messageBlock(activity,error,activity::retryHotSearchPhone).apply {
                    setPadding(horizontalPadding, paddingTop, horizontalPadding, paddingBottom)
                },lp())
            }
            val guessItems=hot?.guesses.orEmpty().map { it.series }
            guesses.visibility=if(guessItems.isEmpty()) View.GONE else View.VISIBLE
            guessesAdapter.submitItems(guessItems,false)
            val availableBoards=hot?.boards.orEmpty()
            boards.visibility=if(availableBoards.isEmpty()) View.GONE else View.VISIBLE
            val selected=availableBoards.firstOrNull { it.name==activity.phoneHotBoardName } ?: availableBoards.firstOrNull()
            val names=availableBoards.map { it.name }
            val rebuilt=names!=shownBoards
            if(rebuilt) {
                shownBoards=names
                choices.removeAllViews(); chips.clear()
                names.forEach { name ->
                    val chip=text(name,13f).apply {
                        gravity=Gravity.CENTER; setPadding(dp(14),dp(9),dp(14),dp(9))
                        setOnClickListener { activity.chooseHotBoardPhone(name) }
                    }
                    chips[name]=chip
                    choices.addView(chip,lp(-2,-2).apply { rightMargin=dp(6) })
                }
            }
            val selectedName=selected?.name.orEmpty()
            if(rebuilt || shownSelected!=selectedName) {
                shownSelected=selectedName
                chips.forEach { (name,chip) ->
                    val on=name==selectedName
                    chip.setTextColor(if(on) PortraitStyle.onChipSelected else PortraitStyle.text)
                    chip.background=PortraitStyle.selectable(activity,if(on) PortraitStyle.chipSelected else PortraitStyle.raised,radius=20)
                    chip.isSelected=on
                }
                strip.post { chips[shownSelected]?.let { strip.scrollTo((it.left-strip.width/2+it.width/2).coerceAtLeast(0),0) } }
            }
            val items=selected?.items.orEmpty().map { it.series }
            boardAdapter.submitItems(items,false)
            val nextTopics=selected?.topics.orEmpty()
            if(shownTopics!=nextTopics) {
                shownTopics=nextTopics
                topics.removeAllViews()
                nextTopics.forEachIndexed { index,topic ->
                    val line=row().apply {
                        setPadding(horizontalPadding,dp(14),horizontalPadding,dp(14))
                        background=PortraitStyle.selectable(activity,Color.TRANSPARENT)
                        setOnClickListener { search(topic.query) }
                        contentDescription="第 ${index+1} 名，${topic.title}，${topic.heat}"
                    }
                    line.addView(text("${index+1}",15f,PortraitStyle.accent),lp(dp(28),-2))
                    line.addView(text(topic.title,14f),lp(0,-2).apply { weight=1f })
                    if(topic.heat.isNotBlank()) line.addView(text(topic.heat,12f,PortraitStyle.muted),lp(-2,-2))
                    topics.addView(line,lp())
                }
            }
        }
    }
}
