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

    fun home(page: PortraitListPage): (Boolean) -> Unit {
        val body=column(); page.addBlock(body)
        body.addView(PortraitUi.pageTitle(activity, "推荐"), lp())
        val empty = PortraitUi.emptyHint(activity, "正在加载推荐…")
        body.addView(empty, lp())
        val adapter = PortraitUi.PosterGrid(activity, loadCover, { activity.openDetailPhone(it) },
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
            val adapter=PortraitUi.PosterGrid(activity,loadCover,{ activity.openDetailPhone(it) },
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
            val adapter = PortraitUi.PosterGrid(activity, loadCover, { activity.openDetailPhone(it) },
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

    private fun section(body: LinearLayout, title: String) {
        body.addView(text(title, 20f).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(10))
        }, lp())
    }

    /** 最近观看按剧集复用卡片，进度和收藏变化只更新各自的内容。 */
    fun mine(page: PortraitListPage): (Boolean) -> Unit {
        val body=column(); page.addBlock(body)
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
        val recent = column()
        section(recent, "最近观看")
        val strip = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val cards = row().apply { gravity = Gravity.TOP; setPadding(dp(16), 0, dp(16), 0) }
        strip.addView(cards)
        strip.viewTreeObserver.addOnScrollChangedListener { activity.refreshPhoneCovers() }
        recent.addView(strip, lp())
        body.addView(recent, lp())
        val retainedCards = mutableMapOf<String, RecentWatchCard>()

        section(body, "收藏")
        val emptyFavorites = PortraitUi.emptyState(activity, R.drawable.ic_phone_star, "还没有收藏", "在剧集页点「收藏」，或者长按海报，想看的就存下来。")
        body.addView(emptyFavorites, lp())
        val favoritesAdapter = PortraitUi.PosterGrid(activity, loadCover, { activity.openDetailPhone(it) }, null)
        page.addGrid(favoritesAdapter)
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
            val favorites = activity.phoneLibrary.favorites()
            if (shownFavorites != favorites) {
                emptyFavorites.visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE
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
        val adapter=PortraitUi.PosterGrid(activity,loadCover,{ activity.openDetailPhone(it) },null)
        page.addGrid(adapter)
        return { _ ->
            val items=activity.phoneLibrary.favorites()
            empty.visibility=if(items.isEmpty()) View.VISIBLE else View.GONE
            adapter.submitItems(items,false)
        }
    }

    fun search(page: PortraitListPage, submit: (String) -> Unit): (Boolean) -> Unit {
        val body=column(); page.addBlock(body)
        val query = activity.phoneQueryText
        body.addView(PortraitUi.pageTitle(activity, if (query.isBlank()) "热搜" else "“$query”"), lp())
        val suggestions = column()
        val handler = Handler(Looper.getMainLooper())
        var pending: Runnable? = null
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
        input.contentDescription = input.hint
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
            override fun onViewDetachedFromWindow(v: View) { pending?.let { handler.removeCallbacks(it) }; activity.cancelSearchSuggestions() }
        })
        val results=column()
        body.addView(results,lp())
        // 数据、加载状态和错误各自更新，刷新或补封面时保留现有网格及 ImageView。
        if(query.isNotBlank()) {
            val errorBody=column()
            results.addView(errorBody,lp())
            val empty=PortraitUi.emptyHint(activity,"")
            results.addView(empty,lp())
            val adapter=PortraitUi.PosterGrid(activity,loadCover,{ activity.openDetailPhone(it) },
                { activity.loadMorePhone() })
            page.addGrid(adapter)
            var shownError=""
            return { _ ->
                val items=activity.catalog
                val error=activity.phoneSearchErrorPhone
                if(shownError!=error) {
                    shownError=error
                    errorBody.removeAllViews()
                    if(error.isNotBlank()) errorBody.addView(PortraitUi.messageBlock(activity,error) { submit(query) },lp())
                }
                empty.text=if(activity.phoneSearchActive) "正在搜索…"
                    else "没有找到“$query”相关的${activity.phoneLibrary.contentType.label}，换个词试试"
                empty.visibility=if(items.isEmpty() && error.isBlank()) View.VISIBLE else View.GONE
                adapter.submitItems(items,activity.hasMore && !activity.phoneSearchActive && error.isBlank())
            }
        }
        val updateHotSearch=renderHotSearch(page,results,::search)
        val historyBody=column()
        page.addBlock(historyBody)
        var shownHistory: List<String>?=null
        return { _ ->
            updateHotSearch()
            val history=activity.phoneLibrary.searches().take(12).map { it.query }
            if(shownHistory!=history) {
                shownHistory=history
                historyBody.removeAllViews()
                if(history.isNotEmpty()) {
                    section(historyBody,"搜索历史")
                    history.forEach { recent ->
                        historyBody.addView(text(recent,14f).apply {
                            setPadding(dp(16),dp(13),dp(16),dp(13))
                            background=PortraitStyle.selectable(activity,Color.TRANSPARENT)
                            setOnClickListener { search(recent) }
                        },lp())
                    }
                }
            }
        }
    }

    /** 缓存首帧、联网刷新及切榜共用网格，只提交剧集差异。 */
    private fun renderHotSearch(page: PortraitListPage,body: LinearLayout,search: (String)->Unit): ()->Unit {
        val loading=PortraitUi.emptyHint(activity,"正在加载热搜…")
        body.addView(loading,lp())
        val errorBody=column()
        body.addView(errorBody,lp())
        fun grid(ranked: Boolean): PortraitUi.PosterGrid {
            val adapter=PortraitUi.PosterGrid(activity,loadCover,{ activity.openDetailPhone(it) },null,ranked=ranked)
            page.addGrid(adapter)
            return adapter
        }
        val guesses=column()
        section(guesses,"猜你想搜")
        page.addBlock(guesses)
        val guessesAdapter=grid(false)
        val boards=column()
        val strip=HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled=false }
        val choices=row().apply { setPadding(dp(16),dp(12),dp(16),dp(8)) }
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
        return {
            val hot=activity.hotSearchPhone
            loading.visibility=if(activity.hotSearchLoadingPhone && hot==null) View.VISIBLE else View.GONE
            val error=activity.hotSearchErrorPhone
            if(shownError!=error) {
                shownError=error
                errorBody.removeAllViews()
                if(error.isNotBlank()) errorBody.addView(PortraitUi.messageBlock(activity,error,activity::retryHotSearchPhone),lp())
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
                        setPadding(dp(16),dp(14),dp(16),dp(14))
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
