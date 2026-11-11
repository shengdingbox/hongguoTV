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
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.hongguotv.core.Series
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
            { activity.loadMorePhone() }, scrollRoot, loadAheadScreens = 2)
        adapter.attach(grid)
        val error = text("", 14f, PortraitStyle.muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(24), dp(16), dp(24))
            background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 10)
            setOnClickListener { activity.retryRecommendationsPhone() }
        }
        body.addView(error, lp())
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
            grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
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
        ) { key,body,scroll ->
            val empty=PortraitUi.emptyHint(activity,"正在读取榜单…")
            body.addView(empty,lp())
            val grid=RecyclerView(activity); body.addView(grid,lp())
            val adapter=PortraitUi.PosterGrid(activity,loadCover,{ activity.openDetailPhone(it) },
                { if(feed.isCurrent(key)) activity.loadMorePhone() },scroll,ranked=true)
            adapter.attach(grid)
            val error=text("",14f,PortraitStyle.muted).apply {
                gravity=Gravity.CENTER; setPadding(dp(16),dp(24),dp(16),dp(24))
                setOnClickListener { if(feed.isCurrent(key)) activity.retryRankPhone() }
            }
            body.addView(error,lp())
            val update: ()->Unit={
                val page=activity.rankPagePhone(key)
                val items=page?.items.orEmpty()
                val current=key==activity.mobileRankSelectedKeyPhone
                val busy=current && activity.rankLoadingPhone
                val problem=activity.rankErrorPhone(key)
                empty.text=if(busy) "正在读取榜单…" else "这个榜单暂时是空的，下拉可重新加载"
                empty.visibility=if(items.isEmpty() && problem.isBlank()) View.VISIBLE else View.GONE
                grid.visibility=if(items.isEmpty()) View.GONE else View.VISIBLE
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
        ) { key, body, scroll ->
            val loading = PortraitUi.emptyHint(activity, "正在加载新剧…")
            body.addView(loading, lp())
            val empty = PortraitUi.emptyState(activity, R.drawable.ic_phone_grid, "这一天没有新剧", "换个日期看看，或下拉刷新")
            body.addView(empty, lp())
            val grid = RecyclerView(activity)
            body.addView(grid, lp())
            val adapter = PortraitUi.PosterGrid(activity, loadCover, { activity.openDetailPhone(it) },
                { if (feed.isCurrent(key)) activity.loadMoreNewReleasesPhone() }, scroll)
            adapter.attach(grid)
            val error = text("", 14f, PortraitStyle.muted).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(24), dp(16), dp(24))
                background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 10)
                setOnClickListener { if (feed.isCurrent(key)) activity.retryNewReleasesPhone() }
            }
            body.addView(error, lp())
            val update: () -> Unit = {
                val page = activity.newReleasePagePhone(key)
                val items = page?.items.orEmpty().map { it.series }
                val selected = activity.newReleaseDatePhone == key
                val busy = if (selected) activity.newReleaseLoadingPhone else page == null
                val problem = if (selected) activity.newReleaseErrorPhone else ""
                loading.visibility = if (busy && items.isEmpty()) View.VISIBLE else View.GONE
                empty.visibility = if (!busy && problem.isBlank() && items.isEmpty()) View.VISIBLE else View.GONE
                grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
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

    /** 切页保留最近观看和收藏的海报控件；记录实际变化时才更新内容。 */
    fun mine(body: LinearLayout, scrollRoot: NestedScrollView? = null): (Boolean) -> Unit {
        var shownHistory: List<WatchProgress>? = null
        var shownFavorites: List<Series>? = null
        return { _ ->
            val history = activity.phoneLibrary.history().take(30)
            val favorites = activity.phoneLibrary.favorites()
            if (shownHistory != history || shownFavorites != favorites) {
                shownHistory = history; shownFavorites = favorites
                body.removeAllViews()
                buildMine(body, scrollRoot, history)
            }
        }
    }

    private fun buildMine(body: LinearLayout, scrollRoot: NestedScrollView?, history: List<WatchProgress>) {
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
        if (history.isNotEmpty()) {
            section(body, "最近观看")
            val strip = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
            val cards = row().apply { gravity = Gravity.TOP; setPadding(dp(16), 0, dp(16), 0) }
            strip.addView(cards)
            strip.viewTreeObserver.addOnScrollChangedListener { activity.refreshPhoneCovers() }
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
                    val menu = PopupMenu(PortraitStyle.popupContext(activity), card)
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

    fun search(body: LinearLayout, submit: (String) -> Unit, scrollRoot: NestedScrollView? = null): (Boolean) -> Unit {
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
            override fun onViewDetachedFromWindow(v: View) { pending?.let { handler.removeCallbacks(it) } }
        })
        val results=column()
        body.addView(results,lp())
        var shown=""
        return { _ ->
            val signature=listOf(activity.catalog,activity.hasMore,activity.phoneSearchActive,activity.phoneSearchErrorPhone,
                activity.hotSearchPhone,activity.hotSearchLoadingPhone,activity.hotSearchErrorPhone,activity.phoneHotBoardName).toString()
            if(signature!=shown) {
                shown=signature; results.removeAllViews()
                if(query.isNotBlank()) {
                    val error=activity.phoneSearchErrorPhone
                    if(error.isNotBlank()) results.addView(PortraitUi.messageBlock(activity,error) { submit(query) },lp())
                    if(activity.catalog.isEmpty() && error.isBlank()) results.addView(PortraitUi.emptyHint(activity,
                        if(activity.phoneSearchActive) "正在搜索…" else "没有找到“$query”相关的短剧，换个词试试"),lp())
                    if(activity.catalog.isNotEmpty()) {
                        val grid=RecyclerView(activity); results.addView(grid,lp())
                        PortraitUi.PosterGrid(activity,loadCover,{ activity.openDetailPhone(it) },{ activity.loadMorePhone() },scrollRoot).apply {
                            items=activity.catalog; attach(grid); submitItems(activity.catalog,activity.hasMore && error.isBlank())
                        }
                    }
                } else {
                    renderHotSearch(results,scrollRoot,::search)
                    val history=activity.phoneLibrary.searches().take(12)
                    if(history.isNotEmpty()) {
                        section(results,"搜索历史")
                        history.forEach { recent ->
                            results.addView(text(recent.query,14f).apply {
                                setPadding(dp(16),dp(13),dp(16),dp(13))
                                background=PortraitStyle.selectable(activity,Color.TRANSPARENT)
                                setOnClickListener { search(recent.query) }
                            },lp())
                        }
                    }
                }
            }
        }
    }

    private fun renderHotSearch(body: LinearLayout,scrollRoot: NestedScrollView?,search: (String)->Unit) {
        val hot=activity.hotSearchPhone
        if(activity.hotSearchLoadingPhone && hot==null) body.addView(PortraitUi.emptyHint(activity,"正在加载热搜…"),lp())
        val error=activity.hotSearchErrorPhone
        if(error.isNotBlank()) body.addView(PortraitUi.messageBlock(activity,error,activity::retryHotSearchPhone),lp())
        if(hot==null) return
        fun grid(items: List<com.hongguotv.core.HotSearchItem>,ranked: Boolean) {
            if(items.isEmpty()) return
            val view=RecyclerView(activity); body.addView(view,lp())
            PortraitUi.PosterGrid(activity,loadCover,{ activity.openDetailPhone(it) },null,scrollRoot,ranked=ranked).apply {
                this.items=items.map { it.series }; attach(view)
            }
        }
        if(hot.guesses.isNotEmpty()) {
            section(body,"猜你想搜")
            grid(hot.guesses,false)
        }
        if(hot.boards.isEmpty()) return
        val strip=HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled=false }
        val choices=row().apply { setPadding(dp(16),dp(12),dp(16),dp(8)) }
        val selected=hot.boards.firstOrNull { it.name==activity.phoneHotBoardName } ?: hot.boards.first()
        var selectedView: View?=null
        hot.boards.forEach { board ->
            val on=board==selected
            val chip=text(board.name,13f,if(on) PortraitStyle.onChipSelected else PortraitStyle.text).apply {
                gravity=Gravity.CENTER; setPadding(dp(14),dp(9),dp(14),dp(9))
                background=PortraitStyle.selectable(activity,if(on) PortraitStyle.chipSelected else PortraitStyle.raised,radius=20)
                isSelected=on
                setOnClickListener { activity.chooseHotBoardPhone(board.name) }
            }
            choices.addView(chip,lp(-2,-2).apply { rightMargin=dp(6) })
            if(on) selectedView=chip
        }
        strip.addView(choices);body.addView(strip,lp())
        strip.post { selectedView?.let { strip.scrollTo((it.left-strip.width/2+it.width/2).coerceAtLeast(0),0) } }
        grid(selected.items,true)
        selected.topics.forEachIndexed { index,topic ->
            val line=row().apply {
                setPadding(dp(16),dp(14),dp(16),dp(14))
                background=PortraitStyle.selectable(activity,Color.TRANSPARENT)
                setOnClickListener { search(topic.query) }
                contentDescription="第 ${index+1} 名，${topic.title}，${topic.heat}"
            }
            line.addView(text("${index+1}",15f,PortraitStyle.accent),lp(dp(28),-2))
            line.addView(text(topic.title,14f),lp(0,-2).apply { weight=1f })
            if(topic.heat.isNotBlank()) line.addView(text(topic.heat,12f,PortraitStyle.muted),lp(-2,-2))
            body.addView(line,lp())
        }
    }
}
