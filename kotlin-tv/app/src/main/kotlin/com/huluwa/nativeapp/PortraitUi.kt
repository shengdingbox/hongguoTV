// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.ListPreloader
import com.bumptech.glide.Priority
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader
import com.bumptech.glide.util.ViewPreloadSizeProvider
import com.hongguotv.core.Series
import kotlin.math.abs

/** 手机统一竖屏界面组件，布局参照第三方 iOS 客户端；跟随系统竖屏与强制竖屏共用。 */
object PortraitUi {
    const val PHONE_LIST_LIMIT = 300

    /** 按设备、方向和用户偏好判断，电视及手机横屏由 TV 页面接管。 */
    fun shouldUse(activity: Activity): Boolean = PhoneUi.shouldUse(activity)

    // ---- 基础视图工具（与 TV 版 dp/text/lp 对齐，但控件尺寸按手机习惯收紧） ----

    fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun column(activity: Activity) = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    fun row(activity: Activity) = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(w, h)

    fun text(activity: Activity, value: String, size: Float, color: Int = PortraitStyle.text): TextView =
        TextView(activity).apply { text = value; textSize = size; setTextColor(color); includeFontPadding = false }

    /** 手机四项导航，新剧使用红果公开上新日历；搜索从推荐页标题进入。 */
    val TABS = listOf(
        Tab("home", "推荐", R.drawable.ic_phone_home),
        Tab("rank", "排行榜", R.drawable.ic_phone_rank),
        Tab("new", "新剧", R.drawable.ic_phone_grid),
        Tab("mine", "我的", R.drawable.ic_phone_star),
    )

    data class Tab(val key: String, val label: String, val icon: Int)

    /** 内容铺满页面，胶囊导航悬浮于内容之上。滚动页自行预留底部空间。 */
    fun scaffold(activity: Activity, activeTab: String, onTab: (String) -> Unit, previousTab: String? = null): Triple<FrameLayout, LinearLayout, LinearLayout> {
        PortraitStyle.refresh(activity)
        val root = FrameLayout(activity).apply { setBackgroundColor(PortraitStyle.background) }
        val content = column(activity)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        val nav = PortraitGlassNavigation(activity, content).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 4), dp(activity, 3), dp(activity, 4), dp(activity, 3))
        }
        TABS.forEach { tab ->
            val item = column(activity).apply {
                gravity = Gravity.CENTER
                contentDescription = tab.label
                isFocusable = true
                tag = "nav-item:${tab.key}"
                background = PortraitStyle.navigationItem(activity)
                setOnClickListener { onTab(tab.key) }
            }
            item.addView(ImageView(activity).apply {
                setImageResource(tab.icon)
                tag = "nav-icon:${tab.key}"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, lp(dp(activity, 25), dp(activity, 25)))
            item.addView(text(activity, tab.label, 9f).apply {
                gravity = Gravity.CENTER
                tag = "nav-label:${tab.key}"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, lp(-2, -2).apply { topMargin = dp(activity, 2) })
            // 每项以 72dp 收拢宽度，窄屏时仍按权重均分可用空间。
            nav.addView(item, lp(dp(activity, 72), -1).apply { weight = 1f })
        }
        root.addView(nav, FrameLayout.LayoutParams(-2, dp(activity, 58), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            leftMargin = dp(activity, 18)
            rightMargin = dp(activity, 18)
            bottomMargin = dp(activity, 12)
        })
        paintNav(nav, activeTab, previousTab)
        PhoneInsets.listen(root) { topInset, bottomInset, imeInset ->
            content.setPadding(0, topInset, 0, imeInset)
            (nav.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = dp(activity, 12) + bottomInset }
            nav.requestLayout()
            nav.visibility = if (imeInset > bottomInset) View.GONE else View.VISIBLE
        }
        return Triple(root, content, nav)
    }

    fun paintNav(nav: LinearLayout, active: String, previous: String? = null) {
        fun navigationTab(tab: String?) = when (tab) {
            "fav" -> "mine"
            "search" -> "home"
            else -> tab
        }
        val selectedTab = navigationTab(active)
        val previousTab = navigationTab(previous)
        (nav as? PortraitGlassNavigation)?.selectItem(
            TABS.indexOfFirst { it.key == selectedTab },
            TABS.indexOfFirst { it.key == previousTab },
        )
        TABS.forEach { tab ->
            val selected = tab.key == selectedTab
            val color = if (selected) PortraitStyle.navigationSelected else PortraitStyle.text
            nav.findViewWithTag<View>("nav-item:${tab.key}")?.isSelected = selected
            (nav.findViewWithTag<View>("nav-icon:${tab.key}") as? ImageView)?.setColorFilter(color)
            (nav.findViewWithTag<View>("nav-label:${tab.key}") as? TextView)?.apply {
                setTextColor(color)
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }

    fun pageTitle(activity: Activity, label: String) = text(activity, label, 30f).apply {
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 10))
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
    }

    /** 手机海报卡片：无外框，圆角海报 + 标题 + 标签；网格与横向条带共用。 */
    class PosterCard(activity: Activity) : LinearLayout(activity) {
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val title = text(activity, "", 14f).apply { maxLines = 1; minLines = 1; ellipsize = TextUtils.TruncateAt.END; setTypeface(null, Typeface.BOLD); setPadding(0, dp(activity, 7), 0, 0) }
        val badge = text(activity, "", 12f, PortraitStyle.muted).apply { maxLines = 1; minLines = 1; ellipsize = TextUtils.TruncateAt.END }

        val saved = ImageView(activity).apply {
            setImageResource(R.drawable.ic_phone_star)
            setColorFilter(PortraitStyle.muted)
            contentDescription = "已收藏"
            visibility = View.GONE
        }
        val rank = text(activity, "", 11f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(activity, 6), 0, dp(activity, 6), 0)
            minWidth = dp(activity, 22)
            minHeight = dp(activity, 20)
            visibility = View.GONE
        }

        val poster: FrameLayout

        init {
            orientation = VERTICAL
            // 海报容器：按卡片实测宽度定高
            poster = object : FrameLayout(activity) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val w = MeasureSpec.getSize(widthMeasureSpec)
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((w / PortraitStyle.POSTER_ASPECT).toInt(), MeasureSpec.EXACTLY))
                }
            }.apply {
                background = PortraitStyle.shape(activity, PortraitStyle.surface, radius = 10).apply { setStroke(0, Color.TRANSPARENT) }
                clipToOutline = true
            }
            poster.addView(image, FrameLayout.LayoutParams(-1, -1))
            poster.addView(rank, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                topMargin = dp(activity, 6); leftMargin = dp(activity, 6)
            })
            addView(poster, lp(-1, -2))
            val titleRow = row(activity)
            titleRow.addView(title, lp(0, -2).apply { weight = 1f })
            titleRow.addView(saved, lp(dp(activity, 9), dp(activity, 9)).apply {
                leftMargin = dp(activity, 4); topMargin = dp(activity, 7)
            })
            addView(titleRow, lp(-1, -2))
            addView(badge, lp(-1, -2))
        }
    }

    /** 海报列表只管理条目，页头及网格由 PortraitListPage 在同一个 RecyclerView 中复用。 */
    class PosterGrid(
        private val activity: MainActivity,
        private val loadCover: (ImageView, String) -> Unit,
        private val open: (Series, ImageView) -> Unit,
        private val more: (() -> Unit)?,
        private val ranked: Boolean = false,
        private val loadAheadScreens: Int = 0,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private data class Cell(val series: Series?, val favorite: Boolean = false, val rank: Int = 0, val finished: Boolean = false)
        private val stableIds = mutableMapOf<String, Long>()
        private var nextStableId = 0L
        private var submitted = emptyList<Cell>()
        private var recycler: RecyclerView? = null
        private val preloadSize = ViewPreloadSizeProvider<String>()
        internal var isUpdating = false
            private set
        internal var onListApplied: () -> Unit = {}
        private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<Cell>() {
            override fun areItemsTheSame(before: Cell, after: Cell) = before.series?.id == after.series?.id
            override fun areContentsTheSame(before: Cell, after: Cell): Boolean {
                val a = before.series; val b = after.series
                // CDN 临时签名轮换不是封面变化，不能让 DiffUtil 把卡片重新绑定一遍。
                return a?.title == b?.title && a?.badge == b?.badge && a?.release == b?.release &&
                    activity.phoneCoverIdentity(a?.cover.orEmpty()) == activity.phoneCoverIdentity(b?.cover.orEmpty()) &&
                    before.favorite == after.favorite && before.rank == after.rank && before.finished == after.finished
            }
        })

        init { setHasStableIds(true) }
        override fun getItemId(position: Int): Long = differ.currentList[position].series?.let { series ->
            stableIds.getOrPut(series.id) { nextStableId++ }
        } ?: -1L

        /** 收藏与排名随不可变条目一起交给后台计算，旧结果由 AsyncListDiffer 自动丢弃。 */
        fun submitItems(next: List<Series>, nextHasMore: Boolean) {
            val retained = next.toList()
            val cells = retained.mapIndexed { index, series ->
                Cell(series, activity.phoneLibrary.favorite(series.id), if (ranked) index + 1 else 0)
            } + when {
                more != null && retained.size >= PHONE_LIST_LIMIT -> listOf(Cell(null, finished = true))
                nextHasMore && more != null -> listOf(Cell(null))
                else -> emptyList()
            }
            if (submitted == cells) return
            submitted = cells
            isUpdating = true
            differ.submitList(cells) {
                isUpdating = false
                onListApplied()
                stableIds.keys.retainAll(differ.currentList.mapNotNull { it.series?.id }.toSet())
                recycler?.post { checkLoadMore() }
            }
        }

        private class Holder(val card: PosterCard) : RecyclerView.ViewHolder(card) {
            var seriesId = ""
            var cover = ""
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is Holder) {
                activity.recyclePhoneCover(holder.card.image)
                holder.seriesId = ""
                holder.cover = ""
                holder.card.image.transitionName = null
            }
            super.onViewRecycled(holder)
        }

        override fun getItemCount() = differ.currentList.size
        override fun getItemViewType(position: Int) = if (differ.currentList[position].series == null) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 1) {
                val frame = FrameLayout(activity)
                frame.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 12) }
                val label = text(activity, "", 13f, PortraitStyle.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(activity, 14), 0, dp(activity, 14)) }
                frame.addView(label, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                return object : RecyclerView.ViewHolder(frame) {}
            }
            val card = PosterCard(activity).apply { background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 6) }
            card.layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                // 间距放在卡片外侧，海报直接铺满卡片宽度。
                leftMargin = dp(activity, 6)
                rightMargin = dp(activity, 6)
                bottomMargin = dp(activity, 17)
            }
            return Holder(card)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (getItemViewType(position) == 1) {
                val label = (holder.itemView as FrameLayout).getChildAt(0) as TextView
                val finished = differ.currentList[position].finished
                label.text = if (finished) "本轮内容已加载完，下拉刷新换一批" else "加载更多…"
                holder.itemView.setOnClickListener(if (finished) null else View.OnClickListener { more?.invoke() })
                return
            }
            val card = (holder as Holder).card
            val cell = differ.currentList[position]
            val item = checkNotNull(cell.series)
            val sameSeries = holder.seriesId == item.id
            holder.seriesId = item.id
            card.image.transitionName = PosterSharedTransition.posterName(item.id)
            preloadSize.setView(card.image)
            card.title.text = item.title
            card.badge.text = item.badge
            card.badge.maxLines = if (item.release != null) 2 else 1
            card.contentDescription = listOf(item.title, item.release?.label.orEmpty()).filter { it.isNotBlank() }.joinToString("，")
            card.saved.visibility = if (cell.favorite) View.VISIBLE else View.GONE
            card.saved.contentDescription = if (activity.phoneLibrary.reserved(item.id)) "已预约" else "已收藏"
            card.rank.visibility = if (ranked) View.VISIBLE else View.GONE
            if (ranked) {
                card.rank.text = "${cell.rank}"
                val color = when (position) {
                    0 -> Color.rgb(242, 173, 33)
                    1 -> Color.rgb(158, 158, 158)
                    2 -> Color.rgb(194, 120, 69)
                    else -> Color.argb(150, 0, 0, 0)
                }
                card.rank.background = PortraitStyle.shape(activity, color, radius = 6)
            }
            // 文案、收藏状态或排名变化时沿用已显示的封面；CDN 签名更新仍使用同一份 Glide 缓存。
            val cover = item.cover.ifBlank { if (sameSeries) holder.cover else "" }
            val sameCover = activity.phoneCoverIdentity(holder.cover) == activity.phoneCoverIdentity(cover)
            if (!sameCover || card.image.drawable == null) {
                holder.cover = cover
                if (sameSeries) activity.updatePhoneCover(card.image, cover)
                else loadCover(card.image, cover)
            }
            // 未展示字段更新时不重绑卡片，交互始终读取当前列表中的最新剧集数据。
            card.setOnClickListener {
                differ.currentList.getOrNull(holder.bindingAdapterPosition)?.series?.let { open(it, card.image) }
            }
            card.setOnLongClickListener {
                val current = differ.currentList.getOrNull(holder.bindingAdapterPosition)?.series ?: return@setOnLongClickListener false
                favoriteMenu(activity, card, current) {
                    card.saved.visibility = if (activity.phoneLibrary.favorite(current.id)) View.VISIBLE else View.GONE
                }
                true
            }
        }

        internal fun observe(view: RecyclerView) {
            recycler = view
            val requests = Glide.with(activity)
            val models = object : ListPreloader.PreloadModelProvider<String> {
                override fun getPreloadItems(position: Int): List<String> {
                    // RecyclerViewPreloader 使用整张列表的位置；页头与其他分区不属于这个海报适配器。
                    var local = position
                    val sections = (view.adapter as? ConcatAdapter)?.adapters ?: listOf(this@PosterGrid)
                    for (section in sections) {
                        if (local < section.itemCount) {
                            val cover = if (section === this@PosterGrid) differ.currentList.getOrNull(local)?.series?.cover else null
                            return if (cover.isNullOrBlank()) emptyList() else listOf(cover)
                        }
                        local -= section.itemCount
                    }
                    return emptyList()
                }

                override fun getPreloadRequestBuilder(item: String): RequestBuilder<*> =
                    GlideArtwork.coverRequest(requests, item, Priority.LOW)
            }
            val lowRam = (activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
            view.addOnScrollListener(RecyclerViewPreloader(requests, models, preloadSize, if (lowRam) 12 else 36))
            view.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy > 0) checkLoadMore()
                }
            })
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> view.post { checkLoadMore() } }
        }

        private fun checkLoadMore() {
            val view = recycler ?: return
            val last = differ.currentList.lastOrNull() ?: return
            if (!view.isShown || more == null || last.series != null || last.finished) return
            val remaining = view.computeVerticalScrollRange() - view.computeVerticalScrollOffset() - view.computeVerticalScrollExtent()
            if (remaining <= maxOf(dp(activity, 240), view.height * loadAheadScreens)) more()
        }
    }

    private fun favoriteMenu(activity: MainActivity, anchor: View, series: Series, changed: () -> Unit) {
        val menu = PopupMenu(PortraitStyle.popupContext(activity), anchor)
        menu.menu.add(0, 0, 0, activity.phoneLibrary.favoriteMenuLabel(series))
        if (!series.upcoming) menu.menu.add(0, 1, 1, "离线缓存")
        menu.setOnMenuItemClickListener { item ->
            if (item.itemId == 1) { PhoneOfflineLibrary.pick(activity, series); return@setOnMenuItemClickListener true }
            activity.phoneFavoriteMonitor.stop()
            activity.phoneLibrary.toggle(series)
            changed()
            activity.renderPhoneTab()
            true
        }
        menu.show()
    }

    /** 推荐、排行和新剧共用原生拖拽进度、松手转圈及收起动画。 */
    class RefreshLayout(
        activity: Activity,
        scroll: View,
        refresh: () -> Unit,
        private val maxDurationMillis: Long = 0L,
        private val minDurationMillis: Long = 0L,
    ) : SwipeRefreshLayout(activity) {
        private val refreshTimeout = Runnable { finishRefresh() }
        private val finishAfterMinimum = Runnable { finishRefresh() }
        private var refreshStartedAt = 0L

        init {
            setColorSchemeColors(PortraitStyle.accent)
            setProgressBackgroundColorSchemeColor(PortraitStyle.background)
            setDistanceToTriggerSync(dp(activity, 80))
            setProgressViewOffset(true, -dp(activity, 40), dp(activity, 64))
            setOnChildScrollUpCallback { _, _ -> scroll.canScrollVertically(-1) }
            setOnRefreshListener {
                refreshStartedAt = SystemClock.uptimeMillis()
                removeCallbacks(finishAfterMinimum)
                removeCallbacks(refreshTimeout)
                if (maxDurationMillis > 0L) postDelayed(refreshTimeout, maxDurationMillis)
                refresh()
            }
            addView(scroll, ViewGroup.LayoutParams(-1, -1))
        }

        /** 动画由下拉手势启动，首屏和分页加载不会主动弹出刷新圈。 */
        fun updateLoading(loading: Boolean) {
            if (loading) return
            val remaining = minDurationMillis - (SystemClock.uptimeMillis() - refreshStartedAt)
            if (isRefreshing && refreshStartedAt != 0L && remaining > 0L) {
                // 缓存或快速响应也保留一段可见的转圈时间，数据仍立即更新。
                removeCallbacks(finishAfterMinimum)
                postDelayed(finishAfterMinimum, remaining)
            } else finishRefresh()
        }

        fun finishRefresh() {
            removeCallbacks(refreshTimeout)
            removeCallbacks(finishAfterMinimum)
            refreshStartedAt = 0L
            isRefreshing = false
        }

        override fun onDetachedFromWindow() {
            finishRefresh()
            super.onDetachedFromWindow()
        }
    }

    /** 其余手机竖屏页面保留原生滚动和顶部下拉刷新手势。 */
    class RefreshScrollView(activity: Activity, private val refresh: () -> Unit) : NestedScrollView(activity) {
        private val threshold = dp(activity, 80)
        private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startedAtTop = false

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    startedAtTop = !canScrollVertically(-1)
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> startedAtTop = false
            }
            val shouldRefresh = event.actionMasked == MotionEvent.ACTION_UP && startedAtTop &&
                event.y - downY > threshold && event.y - downY > abs(event.x - downX) &&
                !canScrollVertically(-1)
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) startedAtTop = false
            if (shouldRefresh) refresh()
            return handled
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_MOVE && startedAtTop) {
                val distance = event.y - downY
                if (distance > touchSlop && distance > abs(event.x - downX) && !canScrollVertically(-1)) return true
            }
            return super.onInterceptTouchEvent(event)
        }
    }

    /** 通用错误/提示块。 */
    fun messageBlock(activity: Activity, label: String, retry: (() -> Unit)? = null): LinearLayout {
        val block = column(activity).apply { setPadding(dp(activity, 14), dp(activity, 28), dp(activity, 14), dp(activity, 18)) }
        block.addView(text(activity, label, 15f, PortraitStyle.muted))
        if (retry != null) {
            val btn = text(activity, "重试", 15f, PortraitStyle.onAccent).apply {
                gravity = Gravity.CENTER
                setPadding(dp(activity, 20), dp(activity, 9), dp(activity, 20), dp(activity, 9))
                background = PortraitStyle.shape(activity, PortraitStyle.accent)
                setOnClickListener { retry() }
            }
            block.addView(btn, lp(-2, -2).apply { topMargin = dp(activity, 14) })
        }
        return block
    }

    fun emptyState(activity: Activity, icon: Int, title: String, description: String = ""): LinearLayout =
        column(activity).apply {
            gravity = Gravity.CENTER
            setPadding(dp(activity, 24), dp(activity, 64), dp(activity, 24), dp(activity, 56))
            addView(ImageView(activity).apply {
                setImageResource(icon)
                setColorFilter(PortraitStyle.muted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, lp(dp(activity, 42), dp(activity, 42)))
            addView(text(activity, title, 18f).apply { setTypeface(null, Typeface.BOLD) }, lp(-2, -2).apply { topMargin = dp(activity, 16) })
            if (description.isNotBlank()) addView(text(activity, description, 13f, PortraitStyle.muted).apply {
                gravity = Gravity.CENTER
                setLineSpacing(dp(activity, 4).toFloat(), 1f)
            }, lp(-1, -2).apply { topMargin = dp(activity, 9) })
        }

    /** 空态图占位（纯文字版，避免引入新图片资源）。 */
    fun emptyHint(activity: Activity, label: String): TextView =
        text(activity, label, 14f, PortraitStyle.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(activity, 40), 0, dp(activity, 40)) }

}
