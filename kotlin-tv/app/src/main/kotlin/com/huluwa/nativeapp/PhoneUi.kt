// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hongguotv.core.Series

/**
 * 手机竖屏页面的公共基建与首页。TV 原页面（showCatalog/renderHome 等）一律不动，
 * 这里另起一套纵向布局：底部导航 + 顶部搜索入口 + 内容滚动区。
 *
 * 约定：所有页面都挂在 MainActivity.root 下，由 PhoneUi 判断「手机竖屏」时接管渲染；
 * 横屏（手机或电视）仍走原有 TV 页面。
 */
object PhoneUi {

    /** 当前是否应使用手机竖屏页面：触屏手持设备 + 竖屏方向。 */
    fun shouldUse(activity: Activity): Boolean {
        val portrait = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        return portrait && TouchControls.isHandheld(activity)
    }

    // ---- 基础视图工具（与 TV 版 dp/text/lp 对齐，但控件尺寸按手机习惯收紧） ----

    fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun column(activity: Activity) = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    fun row(activity: Activity) = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(w, h)

    fun text(activity: Activity, value: String, size: Float, color: Int = TvStyle.text): TextView =
        TextView(activity).apply { text = value; textSize = size; setTextColor(color); includeFontPadding = false }

    fun margin(view: View, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): View {
        (view.layoutParams as? LinearLayout.LayoutParams)?.apply {
            leftMargin = l; topMargin = t; rightMargin = r; bottomMargin = b
        }
        return view
    }

    /** 手机底部导航 Tab 定义：首页 / 排行 / 收藏 / 我的。搜索在首页顶栏，不单独占 Tab。 */
    val TABS = listOf(
        Tab("home", "首页", com.huluwa.nativeapp.R.drawable.ic_nav_home),
        Tab("rank", "排行", com.huluwa.nativeapp.R.drawable.ic_nav_rank),
        Tab("fav", "收藏", com.huluwa.nativeapp.R.drawable.ic_nav_search),
        Tab("mine", "我的", com.huluwa.nativeapp.R.drawable.ic_nav_mine))

    data class Tab(val key: String, val label: String, val icon: Int)

    /**
     * 手机版页面骨架：顶栏（品牌 + 搜索入口）+ 中间滚动内容 + 底部导航。
     * [content] 由调用方填充并自行管理滚动。
     */
    fun scaffold(activity: Activity, activeTab: String, onTab: (String) -> Unit, onSearch: () -> Unit): Triple<FrameLayout, LinearLayout, LinearLayout> {
        val root = FrameLayout(activity).apply { setBackgroundColor(TvStyle.background) }
        val shell = column(activity)
        // 顶栏：品牌 + 版本
        val top = row(activity).apply { setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 8)) }
        val brand = row(activity)
        brand.addView(ImageView(activity).apply { setImageResource(com.huluwa.nativeapp.R.drawable.app_icon) }, lp(dp(activity, 24), dp(activity, 24)).apply { rightMargin = dp(activity, 7) })
        brand.addView(text(activity, "红果", 19f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        top.addView(brand)
        top.addView(android.widget.Space(activity), lp(0, -2).apply { weight = 1f })
        shell.addView(top, lp(-1, -2))
        // 内容区
        val content = column(activity)
        shell.addView(content, lp(-1, 0).apply { weight = 1f })
        // 底部导航
        val nav = row(activity).apply {
            setBackgroundColor(TvStyle.surface)
            setPadding(0, dp(activity, 6), 0, dp(activity, 6))
        }
        TABS.forEach { tab ->
            val item = column(activity).apply { gravity = Gravity.CENTER_HORIZONTAL }
            item.addView(ImageView(activity).apply {
                setImageResource(tab.icon)
                // 选中态着色在 renderNav 里做，这里先挂图
                tag = "nav-icon:${tab.key}"
            }, lp(dp(activity, 22), dp(activity, 22)))
            item.addView(text(activity, tab.label, 11f).apply { gravity = Gravity.CENTER; tag = "nav-label:${tab.key}" }, lp(-2, -2).apply { topMargin = dp(activity, 2) })
            item.setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4))
            item.setOnClickListener { if (tab.key != activeTab) onTab(tab.key) }
            nav.addView(item, lp(0, -2).apply { weight = 1f })
        }
        shell.addView(nav, lp(-1, -2))
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))
        // 搜索入口：顶栏右侧一个圆形按钮，点击进入搜索页（由 MainActivity 路由）
        val searchEntry = ImageView(activity).apply {
            setImageResource(com.huluwa.nativeapp.R.drawable.ic_search)
            background = TvStyle.shape(activity, TvStyle.raised)
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
            setOnClickListener { onSearch() }
            contentDescription = "搜索"
        }
        top.addView(searchEntry, lp(dp(activity, 36), dp(activity, 36)))
        return Triple(root, content, nav)
    }

    /** 底部导航选中态：图标与文字用强调色，其余用弱化色。 */
    fun paintNav(nav: LinearLayout, active: String) {
        TABS.forEach { tab ->
            val selected = tab.key == active
            val color = if (selected) TvStyle.accent else TvStyle.muted
            (nav.findViewWithTag<View>("nav-icon:${tab.key}") as? ImageView)?.setColorFilter(color)
            (nav.findViewWithTag<View>("nav-label:${tab.key}") as? TextView)?.setTextColor(color)
        }
    }

    /** 手机海报卡片：定宽两列网格用。上海报（3:4 圆角）+ 标题 + 标签。 */
    class PosterCard(activity: Activity) : LinearLayout(activity) {
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val title = text(activity, "", 13f).apply { maxLines = 2; minLines = 2; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = true; setPadding(0, dp(activity, 6), 0, 0) }
        val badge = text(activity, "", 11f, TvStyle.muted).apply { maxLines = 1; minLines = 1; ellipsize = TextUtils.TruncateAt.END }

        init {
            orientation = VERTICAL
            setPadding(dp(activity, 6), dp(activity, 6), dp(activity, 6), dp(activity, 8))
            background = TvStyle.shape(activity, TvStyle.surface)
            clipChildren = false
            // 海报容器：按卡片实测宽度定高
            val poster = object : FrameLayout(activity) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val w = MeasureSpec.getSize(widthMeasureSpec)
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((w / TvStyle.POSTER_ASPECT).toInt(), MeasureSpec.EXACTLY))
                }
            }
            poster.addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(poster, lp(-1, -2))
            addView(title, lp(-1, -2))
            addView(badge, lp(-1, -2))
        }
    }

    /** 两列海报网格（含加载更多 footer）。点击卡片进详情。 */
    class PosterGrid(
        private val activity: Activity,
        private val loadCover: (ImageView, String) -> Unit,
        private val open: (Series) -> Unit,
        private val more: (() -> Unit)?,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Series> = emptyList()
        var loading = false
        private val cols = 2

        private class Holder(val card: PosterCard) : RecyclerView.ViewHolder(card)

        override fun getItemCount() = items.size + if (more != null || loading) 1 else 0
        override fun getItemViewType(position: Int) = if (position == items.size) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 1) {
                val frame = FrameLayout(activity)
                frame.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 12) }
                val label = text(activity, "", 13f, TvStyle.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(activity, 14), 0, dp(activity, 14)) }
                frame.addView(label, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                return object : RecyclerView.ViewHolder(frame) {}
            }
            val card = PosterCard(activity)
            card.layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                // 网格横向 spacing 由 RecyclerView padding + itemMargin 实现，这里简单给 bottomMargin
                bottomMargin = dp(activity, 12)
            }
            return Holder(card)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (getItemViewType(position) == 1) {
                val label = (holder.itemView as FrameLayout).getChildAt(0) as TextView
                label.text = if (loading) "正在加载…" else "加载更多…"
                // 每次绑定都要重设：复用的 footer 会把上一页的点击监听带过来
                holder.itemView.setOnClickListener { if (!loading && more != null) more() }
                return
            }
            val card = (holder as Holder).card
            val item = items[position]
            card.title.text = item.title
            card.badge.text = item.badge.ifBlank { "查看剧集" }
            card.contentDescription = item.title
            loadCover(card.image, item.cover)
            card.setOnClickListener { open(item) }
        }

        fun attach(recycler: RecyclerView) {
            recycler.layoutManager = GridLayoutManager(activity, cols)
            recycler.adapter = this
            recycler.setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 8))
            recycler.clipToPadding = false
            recycler.isVerticalScrollBarEnabled = false
            recycler.itemAnimator = null
            // 滚到底自动加载更多
            recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0 || loading || more == null) return
                    val grid = rv.layoutManager as? GridLayoutManager ?: return
                    val last = grid.findLastVisibleItemPosition()
                    if (last >= itemCount - 4) more()
                }
            })
        }
    }

    /** 手机横向条带：标题 + 可横滑海报行。点击进详情。 */
    fun shelf(activity: Activity, title: String, items: List<Series>, loadCover: (ImageView, String) -> Unit, open: (Series) -> Unit, more: (() -> Unit)? = null): LinearLayout {
        val block = column(activity)
        val head = row(activity).apply { setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 4)) }
        head.addView(View(activity).apply { background = TvStyle.shape(activity, TvStyle.accent, radius = 2) }, lp(dp(activity, 3), dp(activity, 13)).apply { rightMargin = dp(activity, 7) })
        head.addView(text(activity, title, 16f).apply { setTypeface(null, Typeface.BOLD) }, lp(0, -2).apply { weight = 1f })
        if (more != null) head.addView(text(activity, "全部 ›", 13f, TvStyle.muted).apply { setOnClickListener { more() } })
        block.addView(head, lp(-1, -2))
        val strip = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; setPadding(dp(activity, 8), 0, dp(activity, 8), 0) }
        val line = row(activity)
        strip.addView(line)
        val cardW = dp(activity, 100)
        items.forEach { item ->
            val card = PosterCard(activity)
            card.image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            card.title.text = item.title
            card.badge.text = item.badge.ifBlank { "查看剧集" }
            card.contentDescription = item.title
            loadCover(card.image, item.cover)
            card.setOnClickListener { open(item) }
            line.addView(card, lp(cardW, -2).apply { rightMargin = dp(activity, 12) })
        }
        block.addView(strip, lp(-1, -2))
        return block
    }

    /** 通用错误/提示块。 */
    fun messageBlock(activity: Activity, label: String, retry: (() -> Unit)? = null): LinearLayout {
        val block = column(activity).apply { setPadding(dp(activity, 14), dp(activity, 28), dp(activity, 14), dp(activity, 18)) }
        block.addView(text(activity, label, 15f, TvStyle.muted))
        if (retry != null) {
            val btn = text(activity, "重试", 15f, TvStyle.background).apply {
                gravity = Gravity.CENTER
                setPadding(dp(activity, 20), dp(activity, 9), dp(activity, 20), dp(activity, 9))
                background = TvStyle.shape(activity, TvStyle.accent)
                setOnClickListener { retry() }
            }
            block.addView(btn, lp(-2, -2).apply { topMargin = dp(activity, 14) })
        }
        return block
    }

    /** 空态图占位（纯文字版，避免引入新图片资源）。 */
    fun emptyHint(activity: Activity, label: String): TextView =
        text(activity, label, 14f, TvStyle.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(activity, 40), 0, dp(activity, 40)) }

    fun toast(activity: Activity, message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
