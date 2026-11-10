// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.hongguotv.core.ContentType
import com.hongguotv.core.Series

/**
 * 手机竖屏内容页集合：首页、排行、收藏、我的、搜索。
 * 每个页面都渲染进 [PhoneUi.scaffold] 骨架，数据与写库复用 MainActivity 已有组件
 * （repository / library / accountManager / artwork），不改动 TV 页面逻辑。
 */
class PhonePages(
    private val activity: MainActivity,
    private val loadCover: (ImageView, String) -> Unit,
) {
    private fun dp(v: Int) = PhoneUi.dp(activity, v)
    private fun text(v: String, size: Float, color: Int = TvStyle.text) = PhoneUi.text(activity, v, size, color)
    private fun column() = PhoneUi.column(activity)
    private fun row() = PhoneUi.row(activity)

    // ---- 首页：接着看 + 收藏有更新 + 热门发现（两列网格） ----

    fun home(scroll: LinearLayout, loading: Boolean, scrollRoot: NestedScrollView? = null) {
        val library = activity.phoneLibrary
        val recent = library.history().filterNot { library.watched(it.series.id) }
        val updates = library.favorites().filter { library.favoriteUpdate(it.id)?.hasUpdate == true }
        val later = library.later()
        val hot = activity.catalog.filterNot { it.id in library.hidden() }

        // 内容类型切换：漫剧 / 短剧
        val typeRow = row().apply { setPadding(dp(14), dp(6), dp(14), dp(2)) }
        listOf(ContentType.COMIC, ContentType.SHORT).forEach { type ->
            val selected = library.contentType == type
            val chip = text(type.label, 13f, if (selected) TvStyle.background else TvStyle.text).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = TvStyle.shape(activity, if (selected) TvStyle.accent else TvStyle.raised)
                setOnClickListener { activity.switchContentTypePhone(type) }
            }
            typeRow.addView(chip, PhoneUi.lp(-2, -2).apply { rightMargin = dp(8) })
        }
        typeRow.addView(PhoneUi.text(activity, "", 13f, TvStyle.muted), PhoneUi.lp(0, -2).apply { weight = 1f })
        scroll.addView(typeRow, PhoneUi.lp(-1, -2))

        if (recent.isNotEmpty()) {
            scroll.addView(shelfSeries("接着看", recent.take(10).map { it.series }, more = { activity.openCollectionPhone("resume") }), PhoneUi.lp(-1, -2))
        }
        if (updates.isNotEmpty()) {
            scroll.addView(shelfSeries("收藏有更新", updates.take(10), more = { activity.openCollectionPhone("updates") }), PhoneUi.lp(-1, -2))
        }
        if (later.isNotEmpty()) {
            scroll.addView(shelfSeries("稍后看", later.take(10), more = { activity.openCollectionPhone("later") }), PhoneUi.lp(-1, -2))
        }

        if (hot.isEmpty()) {
            scroll.addView(PhoneUi.messageBlock(activity, if (loading) "正在加载热门…" else "暂时没有内容，下拉刷新或稍后再试。"), PhoneUi.lp(-1, -2))
            return
        }
        // 热门两列网格
        val head = row().apply { setPadding(dp(14), dp(10), dp(14), dp(4)) }
        head.addView(View(activity).apply { background = TvStyle.shape(activity, TvStyle.accent, radius = 2) }, PhoneUi.lp(dp(3), dp(13)).apply { rightMargin = dp(7) })
        head.addView(text("热门发现 · ${library.contentType.label}", 16f).apply { setTypeface(null, Typeface.BOLD) }, PhoneUi.lp(0, -2).apply { weight = 1f })
        scroll.addView(head, PhoneUi.lp(-1, -2))
        val grid = RecyclerView(activity)
        scroll.addView(grid, PhoneUi.lp(-1, -2))
        val adapter = PhoneUi.PosterGrid(activity, loadCover, { activity.openDetailPhone(it) },
            if (activity.hasMore) { { activity.loadMorePhone() } } else null, scrollRoot)
        adapter.items = hot
        adapter.attach(grid)
    }

    /** 横向条带包装：标题行 + 可滑动卡片行。 */
    private fun shelfSeries(title: String, items: List<Series>, more: (() -> Unit)? = null): LinearLayout {
        val block = PhoneUi.shelf(activity, title, items, loadCover, { activity.openDetailPhone(it) }, more)
        // 竖屏条带上的续播卡片加进度徽标
        return block
    }

    // ---- 排行榜页 ----

    fun rank(scroll: LinearLayout, scrollRoot: NestedScrollView? = null) {
        val head = row().apply { setPadding(dp(14), dp(10), dp(14), dp(4)) }
        head.addView(text("排行榜", 20f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }, PhoneUi.lp(0, -2).apply { weight = 1f })
        // 榜单切换：漫剧 / 真人 / AI
        listOf(com.hongguotv.core.RankBoard.COMIC, com.hongguotv.core.RankBoard.REAL, com.hongguotv.core.RankBoard.AI).forEach { board ->
            val selected = activity.rankBoardPhone == board
            val chip = text(board.label, 12f, if (selected) TvStyle.background else TvStyle.text).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = TvStyle.shape(activity, if (selected) TvStyle.accent else TvStyle.raised)
                setOnClickListener { activity.switchRankBoardPhone(board) }
            }
            head.addView(chip, PhoneUi.lp(-2, -2).apply { leftMargin = dp(6) })
        }
        scroll.addView(head, PhoneUi.lp(-1, -2))

        val items = activity.catalog
        if (activity.rankingPhone != null && items.isNotEmpty()) {
            val subtitle = activity.rankingPhone?.updatedText?.ifBlank { null } ?: "来源：红果${activity.rankBoardPhone.label}热播榜"
            scroll.addView(text(subtitle, 12f, TvStyle.muted).apply { setPadding(dp(14), 0, dp(14), dp(6)) }, PhoneUi.lp(-1, -2))
        }
        if (items.isEmpty()) {
            scroll.addView(PhoneUi.messageBlock(activity, "正在读取最新榜单…"), PhoneUi.lp(-1, -2))
            return
        }
        val grid = RecyclerView(activity)
        scroll.addView(grid, PhoneUi.lp(-1, -2))
        val adapter = PhoneUi.PosterGrid(activity, loadCover, { activity.openDetailPhone(it) },
            if (activity.hasMore) { { activity.loadMorePhone() } } else null, scrollRoot)
        adapter.items = items
        adapter.attach(grid)
    }

    // ---- 收藏页 ----

    fun favorites(scroll: LinearLayout, scrollRoot: NestedScrollView? = null) {
        val library = activity.phoneLibrary
        val head = row().apply { setPadding(dp(14), dp(10), dp(14), dp(4)) }
        head.addView(text("我的收藏", 20f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }, PhoneUi.lp(0, -2).apply { weight = 1f })
        scroll.addView(head, PhoneUi.lp(-1, -2))

        val items = library.favorites().filter { activity.favoriteGroupFilterPhone == null || library.groupOf(it.id) == activity.favoriteGroupFilterPhone }
        val groups = library.groups()
        if (groups.isNotEmpty()) {
            val filterRow = row().apply { setPadding(dp(14), 0, dp(14), dp(6)) }
            (listOf<String?>(null) + groups).forEach { group ->
                val selected = activity.favoriteGroupFilterPhone == group
                val chip = text(group ?: "全部", 12f, if (selected) TvStyle.background else TvStyle.text).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    background = TvStyle.shape(activity, if (selected) TvStyle.accent else TvStyle.raised)
                    setOnClickListener { activity.favoriteGroupFilterPhone = group; activity.renderPhoneTab() }
                }
                filterRow.addView(chip, PhoneUi.lp(-2, -2).apply { rightMargin = dp(8) })
            }
            scroll.addView(filterRow, PhoneUi.lp(-1, -2))
        }
        if (items.isEmpty()) {
            scroll.addView(PhoneUi.emptyHint(activity, "在剧集详情中选择收藏，喜欢的剧就会出现在这里"), PhoneUi.lp(-1, -2))
            return
        }
        val grid = RecyclerView(activity)
        scroll.addView(grid, PhoneUi.lp(-1, -2))
        val adapter = PhoneUi.PosterGrid(activity, loadCover, { activity.openDetailPhone(it) }, null, scrollRoot)
        adapter.items = items
        adapter.attach(grid)
    }

    // ---- 我的页：观看记录 + 稍后看 + 账号 + 设置入口 ----

    /** 「我的」页入口行：左标题 + 可选副标题 + 右箭头，整行可点。 */
    private fun entryRow(title: String, subtitle: String = "", onClick: () -> Unit): LinearLayout {
        val entry = row().apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = TvStyle.shape(activity, TvStyle.surface)
            setOnClickListener { onClick() }
        }
        val label = column()
        // 昵称可能很长，限一行省略，避免把右箭头挤出可视区
        label.addView(text(title, 15f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (subtitle.isNotBlank()) label.addView(text(subtitle, 12f, TvStyle.muted), PhoneUi.lp(0, -2).apply { topMargin = dp(2) })
        entry.addView(label, PhoneUi.lp(0, -2).apply { weight = 1f })
        entry.addView(text("›", 16f, TvStyle.muted))
        return entry
    }

    /** 入口行布局参数：左右各留 14dp，宽度铺满。 */
    private fun entryMargin(top: Int) = PhoneUi.lp(-1, -2).apply { leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(top) }

    fun mine(scroll: LinearLayout) {
        val library = activity.phoneLibrary
        val head = row().apply { setPadding(dp(14), dp(10), dp(14), dp(4)) }
        head.addView(text("我的", 20f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }, PhoneUi.lp(0, -2).apply { weight = 1f })
        scroll.addView(head, PhoneUi.lp(-1, -2))

        val account = activity.phoneAccount
        val accountText = if (account.signedIn) account.accountLabel else "红果账号"
        val accountDetail = if (account.signedIn) "" else "未登录"
        scroll.addView(entryRow(accountText, accountDetail) { account.showLoginDialog(activity) }, entryMargin(top = 6))

        // 设置入口（复用 TV 设置弹层体系的入口子集：主题 / 清晰度 / 倍速 / 弹幕开关 / 账号）
        scroll.addView(entryRow("播放与界面设置") { activity.showPhoneSettings() }, entryMargin(top = 8))

        val recent = library.history().filterNot { library.watched(it.series.id) }
        if (recent.isNotEmpty()) {
            scroll.addView(shelfSeries("最近观看", recent.take(10).map { it.series }, more = { activity.openCollectionPhone("resume") }), PhoneUi.lp(-1, -2).apply { topMargin = dp(10) })
        }
        if (library.later().isNotEmpty()) {
            scroll.addView(shelfSeries("稍后看", library.later().take(10), more = { activity.openCollectionPhone("later") }), PhoneUi.lp(-1, -2))
        }
        if (recent.isEmpty() && library.later().isEmpty()) {
            scroll.addView(PhoneUi.emptyHint(activity, "播放过的剧集会自动保存在这里"), PhoneUi.lp(-1, -2))
        }
    }

    // ---- 搜索页 ----

    fun search(scroll: LinearLayout, input: EditText, onSearch: (String) -> Unit, scrollRoot: NestedScrollView? = null) {
        val library = activity.phoneLibrary
        val history = library.searches()
        val query = activity.phoneQueryText
        if (query.isBlank() && history.isEmpty()) {
            scroll.addView(PhoneUi.emptyHint(activity, "输入名称关键词搜索${library.contentType.label}"), PhoneUi.lp(-1, -2))
        }
        if (query.isNotBlank()) {
            val head = row().apply { setPadding(dp(14), dp(6), dp(14), dp(2)) }
            head.addView(text("“${query}” 的搜索结果", 14f), PhoneUi.lp(0, -2).apply { weight = 1f })
            scroll.addView(head, PhoneUi.lp(-1, -2))
            val items = activity.catalog
            if (items.isEmpty()) {
                scroll.addView(PhoneUi.messageBlock(activity, if (activity.phoneSearchActive) "正在搜索…" else "没有找到相关${library.contentType.label}，换个关键词试试", retry = { onSearch(query) }), PhoneUi.lp(-1, -2))
                return
            }
            val grid = RecyclerView(activity)
            scroll.addView(grid, PhoneUi.lp(-1, -2))
            val adapter = PhoneUi.PosterGrid(activity, loadCover, { activity.openDetailPhone(it) },
                if (activity.hasMore) { { activity.loadMorePhone() } } else null, scrollRoot)
            adapter.items = items
            adapter.attach(grid)
            return
        }
        // 搜索历史
        if (history.isNotEmpty()) {
            val head = row().apply { setPadding(dp(14), dp(10), dp(14), dp(4)) }
            head.addView(text("搜索历史", 15f).apply { setTypeface(null, Typeface.BOLD) }, PhoneUi.lp(0, -2).apply { weight = 1f })
            val clear = text("清空", 13f, TvStyle.muted).apply { setOnClickListener {
                com.huluwa.nativeapp.TvDialog.confirm(activity, "清空搜索历史", "清除本机保存的搜索词？") {
                    library.clearSearches(); activity.renderPhoneSearch()
                }.show()
            } }
            head.addView(clear)
            scroll.addView(head, PhoneUi.lp(-1, -2))
            history.take(12).forEach { entry ->
                val item = text("${entry.type.label} · ${entry.query}", 14f).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(16), dp(11), dp(16), dp(11))
                    background = TvStyle.shape(activity, TvStyle.surface)
                    setOnClickListener { onSearch(entry.query) }
                }
                scroll.addView(item, PhoneUi.lp(-1, -2).apply { leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(5) })
            }
        }
    }

    /** 搜索页顶部的输入框 + 搜索按钮。返回输入框以聚焦。 */
    fun searchBox(onSearch: (String) -> Unit): Pair<LinearLayout, EditText> {
        val bar = row().apply { setPadding(dp(14), dp(8), dp(14), dp(4)) }
        val input = EditText(activity).apply {
            hint = "输入${activity.phoneLibrary.contentType.label}名称"
            textSize = 15f
            setTextColor(TvStyle.text)
            setHintTextColor(TvStyle.muted)
            isSingleLine = true
            maxLines = 1
            background = TvStyle.shape(activity, TvStyle.surface)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        bar.addView(input, PhoneUi.lp(0, -2).apply { weight = 1f })
        val button = text("搜索", 15f, TvStyle.background).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(11), dp(18), dp(11))
            background = TvStyle.shape(activity, TvStyle.accent)
            setOnClickListener { onSearch(input.text.toString().trim()) }
        }
        bar.addView(button, PhoneUi.lp(-2, -2).apply { leftMargin = dp(10) })
        input.setOnEditorActionListener { _, _, _ -> onSearch(input.text.toString().trim()); true }
        return bar to input
    }
}
