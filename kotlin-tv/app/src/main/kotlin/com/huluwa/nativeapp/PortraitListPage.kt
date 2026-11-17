// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.os.Parcelable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 页头、提示及海报共用一个有限高度的 RecyclerView，只创建可见行。 */
class PortraitListPage(private val activity: MainActivity) : FrameLayout(activity) {
    val horizontalContentPadding = PortraitUi.dp(activity, 10)
    private val sections = ConcatAdapter(ConcatAdapter.Config.Builder()
        .setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS).build())
    private val grid = GridLayoutManager(activity, 3)
    val recycler = RecyclerView(activity).apply {
        layoutManager = grid
        adapter = sections
        itemAnimator = null
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setItemViewCacheSize(9)
    }
    private var pendingPosition: Int? = null
    private var pendingLayoutState: Parcelable? = null
    private var restoringLayoutState = false
    val isRestoringPosition get() = pendingPosition != null || pendingLayoutState != null || restoringLayoutState ||
        recycler.isComputingLayout || recycler.isLayoutRequested ||
        sections.adapters.any { it is PortraitUi.PosterGrid && it.isUpdating }
    var onPositionChanged: ((Int) -> Unit)? = null
    // 零高度提示行也会计入滚动条估算，列表在顶部时必须明确记为零。
    val position get() = if (recycler.canScrollVertically(-1)) recycler.computeVerticalScrollOffset() else 0

    init {
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                var offset = position
                sections.adapters.forEach { section ->
                    if (offset < section.itemCount) {
                        return if (section is PortraitUi.PosterGrid && section.getItemViewType(offset) == 0) 1 else 3
                    }
                    offset -= section.itemCount
                }
                return 3
            }
        }
        addView(recycler, LayoutParams(-1, -1))
        PhoneInsets.listen(this) { _, bottomInset, _ ->
            recycler.setPadding(horizontalContentPadding, 0, horizontalContentPadding,
                PortraitUi.dp(activity, 92) + bottomInset)
        }
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                onPositionChanged?.invoke(position)
                if (dy != 0) activity.refreshPhoneCovers()
            }
        })
    }

    fun addBlock(view: View) {
        sections.addAdapter(object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            init { setHasStableIds(true) }
            override fun getItemId(position: Int) = 0L
            override fun getItemCount() = 1
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                object : RecyclerView.ViewHolder(FrameLayout(activity).apply {
                    layoutParams = RecyclerView.LayoutParams(-1, -2)
                }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val container = holder.itemView as FrameLayout
                if (view.parent === container) return
                (view.parent as? ViewGroup)?.removeView(view)
                container.removeAllViews()
                container.addView(view, LayoutParams(-1, -2))
            }
        })
    }

    fun addRows(adapter: RecyclerView.Adapter<*>) { sections.addAdapter(adapter) }

    fun addGrid(adapter: PortraitUi.PosterGrid) {
        sections.addAdapter(adapter)
        adapter.onListApplied = { recycler.post { applyPendingPosition() } }
        adapter.observe(recycler)
    }

    fun captureLayoutState(): Parcelable? = grid.onSaveInstanceState()

    /** 共享封面返回时优先恢复精确锚点，避免滚动条的估算偏移把原卡片移出屏幕。 */
    fun restoreLayoutState(state: Parcelable) {
        pendingPosition = null
        pendingLayoutState = state
        recycler.post { applyPendingPosition() }
    }

    fun restorePosition(y: Int) {
        if (pendingLayoutState != null || restoringLayoutState) return
        pendingPosition = y.coerceAtLeast(0)
        recycler.post { applyPendingPosition() }
    }

    private fun applyPendingPosition() {
        val y = pendingPosition
        val state = pendingLayoutState
        if (y == null && state == null) return
        if (sections.adapters.any { it is PortraitUi.PosterGrid && it.isUpdating }) return
        if (recycler.isComputingLayout || !recycler.isLaidOut) {
            recycler.postOnAnimation { applyPendingPosition() }
            return
        }
        pendingPosition = null
        recycler.stopScroll()
        if (state != null) {
            pendingLayoutState = null
            restoringLayoutState = true
            recycler.doOnNextLayout {
                restoringLayoutState = false
                onPositionChanged?.invoke(position)
            }
            grid.onRestoreInstanceState(state)
            recycler.requestLayout()
            return
        }
        recycler.scrollBy(0, checkNotNull(y) - position)
        onPositionChanged?.invoke(position)
    }

    override fun canScrollVertically(direction: Int) = recycler.canScrollVertically(direction)
}
