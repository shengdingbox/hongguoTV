// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/** 原生横向分页与独立纵向列表；共用标题可折叠，Tab 始终留在分页内容上方。 */
class PortraitFeedPager(
    private val activity: MainActivity,
    title: String,
    tabs: View,
    private val keys: () -> List<String>,
    private val selected: () -> String,
    private val select: (String) -> Unit,
    private val updateTabs: (String) -> Unit,
    private val loading: () -> Boolean,
    private val refresh: () -> Unit,
    private val buildPage: (String, PortraitListPage) -> (() -> Unit),
) : FrameLayout(activity), NestedScrollingParent3 {
    private val titleView = PortraitUi.pageTitle(activity, title)
    private val header = PortraitUi.column(activity).apply {
        setBackgroundColor(PortraitStyle.background)
        addView(titleView, PortraitUi.lp())
        addView(tabs, PortraitUi.lp())
    }
    private val pager = ViewPager2(activity).apply {
        orientation = ViewPager2.ORIENTATION_HORIZONTAL
        offscreenPageLimit = 1
    }
    private val nestedParent = NestedScrollingParentHelper(this)
    private var pageKeys = emptyList<String>()
    private var syncing = false
    private var pendingUpdate = false
    private var collapsed = 0
    private val pages = mutableMapOf<String, Page>()
    private val positions = mutableMapOf<String, Int>()
    private val ids = mutableMapOf<String, Long>()
    private var nextId = 0L
    var onPositionChanged: ((Int) -> Unit)? = null
    private val currentPage get() = pageKeys.getOrNull(pager.currentItem)?.let { pages[it] }
    val position get() = collapsed + (currentPage?.scroll?.position ?: 0)
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { onPositionChanged?.invoke(position) }

    private class Page(val scroll: PortraitListPage, val refresh: PortraitUi.RefreshLayout, val update: () -> Unit)
    private class Holder(val container: FrameLayout) : RecyclerView.ViewHolder(container) {
        var key: String? = null
        var page: Page? = null
    }

    private fun release(holder: Holder) {
        holder.key?.let { key ->
            holder.page?.let { page ->
                positions[key] = page.scroll.position
                if (pages[key] === page) pages.remove(key)
            }
        }
        holder.container.removeAllViews()
        holder.key = null
        holder.page = null
    }

    private val adapter = object : RecyclerView.Adapter<Holder>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = pageKeys.size
        override fun getItemId(position: Int) = ids.getOrPut(pageKeys[position]) { nextId++ }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(FrameLayout(activity).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -1)
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val key = pageKeys[position]
            if (holder.key == key) {
                holder.page?.let { updatePage(key, it) }
                return
            }
            release(holder)
            val scroll = PortraitListPage(activity).apply {
                onPositionChanged = { this@PortraitFeedPager.onPositionChanged?.invoke(this@PortraitFeedPager.position) }
            }
            val update = buildPage(key, scroll)
            val refreshLayout = PortraitUi.RefreshLayout(activity, scroll, {
                if (isCurrent(key)) refresh()
            }).apply {
                setOnChildScrollUpCallback { _, _ -> collapsed > 0 || scroll.canScrollVertically(-1) }
            }
            val page = Page(scroll, refreshLayout, update)
            holder.key = key
            holder.page = page
            pages[key] = page
            holder.container.addView(refreshLayout, LayoutParams(-1, -1))
            updatePage(key, page)
            positions[key]?.let { y -> scroll.post { if (pages[key] === page) scroll.restorePosition(y) } }
        }
        override fun onViewRecycled(holder: Holder) { release(holder) }
    }

    init {
        addView(pager)
        addView(header)
        pager.adapter = adapter
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (syncing) return
                val key = pageKeys.getOrNull(position) ?: return
                updateTabs(key)
                if (key != selected()) select(key)
                updatePages()
                onPositionChanged?.invoke(this@PortraitFeedPager.position)
            }
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                activity.refreshPhoneCovers()
            }
            override fun onPageScrollStateChanged(state: Int) {
                updatePages()
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    if (pendingUpdate) post { update() }
                    // 恢复当前页的自动分页判断，避免滑动期间加载相邻页的数据。
                    currentPage?.scroll?.requestLayout()
                }
            }
        })
    }

    fun isCurrent(key: String) = pager.scrollState == ViewPager2.SCROLL_STATE_IDLE &&
        pageKeys.getOrNull(pager.currentItem) == key

    /** 点击页签使用与手指横滑相同的原生平滑翻页。 */
    fun selectPage(key: String) {
        val index = pageKeys.indexOf(key)
        if (index >= 0 && index != pager.currentItem) pager.setCurrentItem(index, true)
    }

    fun update() {
        val nextKeys = keys().toList()
        val selectedKey = selected()
        updateTabs(selectedKey)
        if (nextKeys != pageKeys) {
            if (pager.scrollState != ViewPager2.SCROLL_STATE_IDLE || (pager.getChildAt(0) as RecyclerView).isComputingLayout) {
                pendingUpdate = true
                if (pager.scrollState == ViewPager2.SCROLL_STATE_IDLE) post { update() }
                return
            }
            val previousKeys = pageKeys
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = previousKeys.size
                override fun getNewListSize() = nextKeys.size
                override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                    previousKeys[oldPosition] == nextKeys[newPosition]
                // 页面内容由 updatePages 就地更新，日期/榜单位置变化不重绑整个页面。
                override fun areContentsTheSame(oldPosition: Int, newPosition: Int) = true
            })
            syncing = true
            pageKeys = nextKeys
            positions.keys.retainAll(nextKeys.toSet())
            diff.dispatchUpdatesTo(adapter)
            pager.setCurrentItem(nextKeys.indexOf(selectedKey).coerceAtLeast(0), false)
            syncing = false
        } else {
            val index = pageKeys.indexOf(selectedKey)
            if (index >= 0 && index != pager.currentItem) {
                syncing = true
                pager.setCurrentItem(index, false)
                syncing = false
            }
        }
        pendingUpdate = false
        updatePages()
    }

    private fun updatePage(key: String, page: Page) {
        page.update()
        page.refresh.isEnabled = isCurrent(key)
        page.refresh.updateLoading(key == selected() && loading())
    }

    private fun updatePages() { pages.toMap().forEach { (key, page) -> updatePage(key, page) } }
    fun finishRefresh() { pages.values.forEach { it.refresh.finishRefresh() } }

    /** 冷启动可展开标题，同时扣除原来的标题收起距离，保留列表位置。 */
    fun restorePosition(y: Int, expandTitle: Boolean = false) {
        val savedCollapsed = y.coerceIn(0, titleView.measuredHeight)
        val targetCollapsed = if (expandTitle) 0 else savedCollapsed
        moveHeader(targetCollapsed - collapsed)
        currentPage?.scroll?.restorePosition((y - savedCollapsed).coerceAtLeast(0))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(scrollListener)
    }
    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        finishRefresh()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        header.measure(exactWidth, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val tabHeight = header.measuredHeight - titleView.measuredHeight
        pager.measure(exactWidth, MeasureSpec.makeMeasureSpec((height - tabHeight).coerceAtLeast(0), MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        collapsed = collapsed.coerceIn(0, titleView.measuredHeight)
        header.layout(0, -collapsed, width, header.measuredHeight - collapsed)
        pager.layout(0, header.bottom, width, header.bottom + pager.measuredHeight)
    }

    private fun moveHeader(dy: Int): Int {
        val next = (collapsed + dy).coerceIn(0, titleView.measuredHeight)
        val consumed = next - collapsed
        if (consumed != 0) {
            collapsed = next
            header.offsetTopAndBottom(-consumed)
            pager.offsetTopAndBottom(-consumed)
            header.elevation = if (collapsed > 0) PortraitUi.dp(activity, 3).toFloat() else 0f
            onPositionChanged?.invoke(position)
            activity.refreshPhoneCovers()
        }
        return consumed
    }

    // 上滑先收起共用标题，下滑到列表顶部后再展开；剩余距离继续交给下拉刷新。
    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int) =
        (axes and ViewCompat.SCROLL_AXIS_VERTICAL) != 0
    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
        nestedParent.onNestedScrollAccepted(child, target, axes, type)
    }
    override fun onStopNestedScroll(target: View, type: Int) { nestedParent.onStopNestedScroll(target, type) }
    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        if (dy > 0) consumed[1] += moveHeader(dy)
    }
    override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int,
                                dyUnconsumed: Int, type: Int, consumed: IntArray) {
        if (dyUnconsumed < 0) consumed[1] += moveHeader(dyUnconsumed)
    }
    override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int,
                                dyUnconsumed: Int, type: Int) {
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, IntArray(2))
    }
    override fun onStartNestedScroll(child: View, target: View, axes: Int) =
        onStartNestedScroll(child, target, axes, ViewCompat.TYPE_TOUCH)
    override fun onNestedScrollAccepted(child: View, target: View, axes: Int) {
        onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH)
    }
    override fun onStopNestedScroll(target: View) { onStopNestedScroll(target, ViewCompat.TYPE_TOUCH) }
    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray) {
        onNestedPreScroll(target, dx, dy, consumed, ViewCompat.TYPE_TOUCH)
    }
    override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int) {
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, ViewCompat.TYPE_TOUCH)
    }
    override fun getNestedScrollAxes() = nestedParent.nestedScrollAxes
    override fun onNestedFling(target: View, velocityX: Float, velocityY: Float, consumed: Boolean) = false
    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float) = false
}
