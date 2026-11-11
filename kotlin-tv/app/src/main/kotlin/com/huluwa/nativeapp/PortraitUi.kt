// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hongguotv.core.Series
import kotlin.math.abs

/** 强制竖屏的独立界面组件，布局参照开心果 iOS；不参与自动方向和 TV 页面渲染。 */
object PortraitUi {

    /** 仅接管用户明确选择的强制竖屏模式。 */
    fun shouldUse(activity: Activity): Boolean =
        TouchControls.isHandheld(activity) && PhoneUi.orientationMode(activity) == OrientationMode.PORTRAIT

    // ---- 基础视图工具（与 TV 版 dp/text/lp 对齐，但控件尺寸按手机习惯收紧） ----

    fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun column(activity: Activity) = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    fun row(activity: Activity) = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(w, h)

    fun text(activity: Activity, value: String, size: Float, color: Int = PortraitStyle.text): TextView =
        TextView(activity).apply { text = value; textSize = size; setTextColor(color); includeFontPadding = false }

    /** 参考图的五项导航；新剧暂时展示空数据。 */
    val TABS = listOf(
        Tab("home", "推荐", R.drawable.ic_phone_home),
        Tab("rank", "排行榜", R.drawable.ic_phone_rank),
        Tab("new", "新剧", R.drawable.ic_phone_grid),
        Tab("mine", "我的", R.drawable.ic_phone_star),
        Tab("search", "搜索", R.drawable.ic_search),
    )

    data class Tab(val key: String, val label: String, val icon: Int)

    /** 内容铺满页面，胶囊导航悬浮于内容之上。滚动页自行预留底部空间。 */
    fun scaffold(activity: Activity, activeTab: String, onTab: (String) -> Unit): Triple<FrameLayout, LinearLayout, LinearLayout> {
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
                background = PortraitStyle.navigationItem(activity, selected = false)
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
            nav.addView(item, lp(0, -1).apply { weight = 1f })
        }
        root.addView(nav, FrameLayout.LayoutParams(-1, dp(activity, 58), Gravity.BOTTOM).apply {
            leftMargin = dp(activity, 18)
            rightMargin = dp(activity, 18)
            bottomMargin = dp(activity, 12)
        })
        paintNav(nav, activeTab)
        PhoneInsets.listen(root) { topInset, bottomInset, imeInset ->
            content.setPadding(0, topInset, 0, imeInset)
            (nav.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = dp(activity, 12) + bottomInset }
            nav.requestLayout()
            nav.visibility = if (imeInset > bottomInset) View.GONE else View.VISIBLE
        }
        return Triple(root, content, nav)
    }

    fun paintNav(nav: LinearLayout, active: String) {
        TABS.forEach { tab ->
            val selected = tab.key == active || active == "fav" && tab.key == "mine"
            val color = if (selected) PortraitStyle.accent else PortraitStyle.text
            nav.findViewWithTag<View>("nav-item:${tab.key}")?.apply {
                isSelected = selected
                background = PortraitStyle.navigationItem(context, selected)
            }
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

    /** 强制竖屏统一使用三列海报网格（含加载更多 footer）。点击卡片进详情。
     *  [scrollParent] 是页面外层的竖向滚动容器：网格放进 NestedScrollView 时高度随内容展开、
     *  自身不滚，拖动由外层接管，所以「加载更多」也要改看外层的滚动位置。 */
    class PosterGrid(
        private val activity: MainActivity,
        private val loadCover: (ImageView, String) -> Unit,
        private val open: (Series) -> Unit,
        private val more: (() -> Unit)?,
        private val scrollParent: NestedScrollView? = null,
        private val ranked: Boolean = false,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Series> = emptyList()
        private var hasMore = more != null
        private var favoriteIds: Set<String> = emptySet()

        /** 保留未变化的卡片与 ImageView；刷新、追加分页都不再重建整张网格。 */
        fun submitItems(next: List<Series>, nextHasMore: Boolean) {
            val previous = items
            val nextItems = next
            val nextFavoriteIds = nextItems.filter { activity.phoneLibrary.favorite(it.id) }.map { it.id }.toSet()
            val previousFooter = hasMore
            val nextFooter = nextHasMore && more != null
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = previous.size + if (previousFooter) 1 else 0
                override fun getNewListSize() = nextItems.size + if (nextFooter) 1 else 0
                override fun areItemsTheSame(oldPosition: Int, newPosition: Int): Boolean {
                    if (oldPosition == previous.size || newPosition == nextItems.size) {
                        return oldPosition == previous.size && newPosition == nextItems.size
                    }
                    return previous[oldPosition].id == nextItems[newPosition].id
                }
                override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean =
                    if (oldPosition == previous.size) true else {
                        val id = previous[oldPosition].id
                        previous[oldPosition] == nextItems[newPosition] &&
                            (id in favoriteIds) == (id in nextFavoriteIds) &&
                            (!ranked || oldPosition == newPosition)
                    }
            })
            items = nextItems
            favoriteIds = nextFavoriteIds
            hasMore = nextFooter
            diff.dispatchUpdatesTo(this)
        }

        private class Holder(val card: PosterCard) : RecyclerView.ViewHolder(card)

        override fun getItemCount() = items.size + if (hasMore) 1 else 0
        override fun getItemViewType(position: Int) = if (position == items.size) 1 else 0

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
                label.text = "加载更多…"
                // 每次绑定都要重设：复用的 footer 会把上一页的点击监听带过来
                holder.itemView.setOnClickListener { if (hasMore && more != null) more() }
                return
            }
            val card = (holder as Holder).card
            val item = items[position]
            card.title.text = item.title
            card.badge.text = item.badge
            card.contentDescription = item.title
            card.saved.visibility = if (activity.phoneLibrary.favorite(item.id)) View.VISIBLE else View.GONE
            card.rank.visibility = if (ranked) View.VISIBLE else View.GONE
            if (ranked) {
                card.rank.text = "${position + 1}"
                val color = when (position) {
                    0 -> Color.rgb(242, 173, 33)
                    1 -> Color.rgb(158, 158, 158)
                    2 -> Color.rgb(194, 120, 69)
                    else -> Color.argb(150, 0, 0, 0)
                }
                card.rank.background = PortraitStyle.shape(activity, color, radius = 6)
            }
            loadCover(card.image, item.cover)
            card.setOnClickListener { open(item) }
            card.setOnLongClickListener {
                favoriteMenu(activity, card, item) {
                    card.saved.visibility = if (activity.phoneLibrary.favorite(item.id)) View.VISIBLE else View.GONE
                }
                true
            }
        }

        fun attach(recycler: RecyclerView) {
            val layoutManager = GridLayoutManager(activity, 3)
            layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (getItemViewType(position) == 1) layoutManager.spanCount else 1
            }
            recycler.layoutManager = layoutManager
            recycler.adapter = this
            recycler.setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 10), dp(activity, 8))
            recycler.clipToPadding = false
            recycler.isVerticalScrollBarEnabled = false
            recycler.itemAnimator = null
            // 外层是 NestedScrollView 时网格随内容展开，整页滚动由外层接管。
            recycler.isNestedScrollingEnabled = false
            val checkLoadMore = {
                if (more != null && hasMore && recycler.isShown) {
                    val nearEnd = if (scrollParent != null) {
                        val content = scrollParent.getChildAt(0)
                        content != null && content.bottom + scrollParent.paddingBottom -
                            (scrollParent.scrollY + scrollParent.height) <= dp(activity, 240)
                    } else {
                        val last = layoutManager.findLastVisibleItemPosition()
                        last != RecyclerView.NO_POSITION && last >= itemCount - 4
                    }
                    if (nearEnd) more()
                }
            }
            recycler.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                // 布局结束后再判断，内容不足一屏时也能继续分页。
                recycler.post { checkLoadMore() }
            }
            if (scrollParent != null) {
                scrollParent.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, y, _, oldY ->
                    if (y > oldY) checkLoadMore()
                })
            } else {
                recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                        if (dy > 0) checkLoadMore()
                    }
                })
            }
        }
    }

    private fun favoriteMenu(activity: MainActivity, anchor: View, series: Series, changed: () -> Unit) {
        val menu = PopupMenu(activity, anchor)
        menu.menu.add(if (activity.phoneLibrary.favorite(series.id)) "取消收藏" else "收藏")
        menu.setOnMenuItemClickListener {
            activity.phoneFavoriteMonitor.stop()
            activity.phoneLibrary.toggle(series)
            changed()
            activity.renderPhoneTab()
            true
        }
        menu.show()
    }

    /** 只在强制竖屏创建，沿用原生滚动并在顶部下拉时刷新。 */
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
            val btn = text(activity, "重试", 15f, PortraitStyle.background).apply {
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
