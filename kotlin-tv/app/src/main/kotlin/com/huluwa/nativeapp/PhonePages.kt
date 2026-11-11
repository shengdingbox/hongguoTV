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

    fun home(scroll: LinearLayout, scrollRoot: NestedScrollView? = null): (Boolean) -> Unit {
        val library = activity.phoneLibrary
        val recent = library.history().filterNot { library.watched(it.series.id) }
        val updates = library.favorites().filter { library.favoriteUpdate(it.id)?.hasUpdate == true }
        val later = library.later()

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

        // 空态与网格各建一次；缓存刷新回来只更新内容，不移除已显示的海报。
        val message = text("", 15f, TvStyle.muted).apply { setPadding(dp(14), dp(28), dp(14), dp(18)) }
        scroll.addView(message, PhoneUi.lp(-1, -2))
        // 热门两列网格
        val head = row().apply { setPadding(dp(14), dp(10), dp(14), dp(4)) }
        head.addView(View(activity).apply { background = TvStyle.shape(activity, TvStyle.accent, radius = 2) }, PhoneUi.lp(dp(3), dp(13)).apply { rightMargin = dp(7) })
        head.addView(text("热门发现 · ${library.contentType.label}", 16f).apply { setTypeface(null, Typeface.BOLD) }, PhoneUi.lp(0, -2).apply { weight = 1f })
        scroll.addView(head, PhoneUi.lp(-1, -2))
        val grid = RecyclerView(activity)
        scroll.addView(grid, PhoneUi.lp(-1, -2))
        val adapter = PhoneUi.PosterGrid(activity, loadCover, { activity.openDetailPhone(it) },
            { activity.loadMorePhone() }, scrollRoot)
        adapter.attach(grid)
        return { loading ->
            val hot = activity.catalog.filterNot { it.id in library.hidden() }
            message.text = if (loading) "正在加载热门…" else "暂时没有内容，下拉刷新或稍后再试。"
            message.visibility = if (hot.isEmpty()) View.VISIBLE else View.GONE
            head.visibility = if (hot.isEmpty()) View.GONE else View.VISIBLE
            grid.visibility = if (hot.isEmpty()) View.GONE else View.VISIBLE
            adapter.submitItems(hot, activity.hasMore)
        }
    }

    /** 横向条带包装：标题行 + 可滑动卡片行。 */
    private fun shelfSeries(title: String, items: List<Series>, more: (() -> Unit)? = null): LinearLayout {
        val block = PhoneUi.shelf(activity, title, items, loadCover, { activity.openDetailPhone(it) }, more)
        // 竖屏条带上的续播卡片加进度徽标
        return block
    }

    // ---- 排行榜页 ----

    fun rank(scroll: LinearLayout, scrollRoot: NestedScrollView? = null): (Boolean) -> Unit {
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

        val subtitle = text("", 12f, TvStyle.muted).apply { setPadding(dp(14), 0, dp(14), dp(6)) }
        scroll.addView(subtitle, PhoneUi.lp(-1, -2))
        val message = PhoneUi.messageBlock(activity, "正在读取最新榜单…")
        scroll.addView(message, PhoneUi.lp(-1, -2))
        val grid = RecyclerView(activity)
        scroll.addView(grid, PhoneUi.lp(-1, -2))
        val adapter = PhoneUi.PosterGrid(activity, loadCover, { activity.openDetailPhone(it) },
            { activity.loadMorePhone() }, scrollRoot)
        adapter.attach(grid)
        return { _ ->
            val items = activity.catalog
            subtitle.text = activity.rankingPhone?.updatedText?.ifBlank { null } ?: "来源：红果${activity.rankBoardPhone.label}热播榜"
            subtitle.visibility = if (activity.rankingPhone != null && items.isNotEmpty()) View.VISIBLE else View.GONE
            message.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            adapter.submitItems(items, activity.hasMore)
        }
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

    // ---- 我的页：账号卡片 + 内容统计 + 偏好/关于分组入口 ----

    /** 分组小标题：比正文弱一档，用来把若干入口行归到同一组。 */
    private fun sectionTitle(parent: LinearLayout, title: String) {
        parent.addView(text(title, 13f, TvStyle.muted).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(18), dp(16), dp(18), dp(6))
        }, PhoneUi.lp(-1, -2))
    }

    /** 入口行统一高度：单行（无副标题）与双行（带副标题）都按这一高度排版，
     *  不能再只用 minimumHeight——双行的实际内容高度会超过它，同组里就忽高忽低。 */
    private fun entryHeight() = dp(62)

    /** 一张圆角卡片里纵向排列若干入口行，行间用细分割线隔开。 */
    private fun groupCard(parent: LinearLayout, rows: List<View>) {
        val card = column().apply {
            background = TvStyle.shape(activity, TvStyle.surface, radius = 14)
        }
        rows.forEachIndexed { index, line ->
            if (index > 0) {
                card.addView(View(activity).apply { setBackgroundColor(TvStyle.outline) },
                    PhoneUi.lp(-1, dp(1)).apply { leftMargin = dp(16) })
            }
            card.addView(line, PhoneUi.lp(-1, entryHeight()))
        }
        parent.addView(card, PhoneUi.lp(-1, -2).apply { leftMargin = dp(14); rightMargin = dp(14) })
    }

    /** 入口行：左标题 + 可选副标题 + 右侧当前值 + 箭头，整行可点。
     *  高度由 [groupCard] 按 [entryHeight] 固定，标题区填满行高并垂直居中。 */
    private fun entryRow(title: String, subtitle: String = "", value: String? = null, onClick: () -> Unit): LinearLayout {
        val entry = row().apply {
            isBaselineAligned = false
            // 上下内边距交给固定行高承担，避免双行内容把行撑高
            setPadding(dp(16), 0, dp(14), 0)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val label = column().apply { gravity = Gravity.CENTER_VERTICAL }
        // 昵称/标题可能很长，限一行省略，避免把右箭头挤出可视区
        label.addView(text(title, 15f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (subtitle.isNotBlank()) {
            // 纵向容器中的副标题占满可用宽度，避免零宽测量导致换行、挤偏标题。
            label.addView(text(subtitle, 12f, TvStyle.muted).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, PhoneUi.lp(-1, -2).apply { topMargin = dp(2) })
        }
        entry.addView(label, PhoneUi.lp(0, -1).apply { weight = 1f })
        if (value != null) {
            entry.addView(text(value, 13f, TvStyle.muted).apply {
                maxLines = 1
                maxWidth = dp(150)
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
            }, PhoneUi.lp(-2, -2).apply { leftMargin = dp(8) })
        }
        // 箭头按自身高度居中：默认 WRAP_CONTENT 已经能靠父行的 gravity 对齐，不用额外高度
        entry.addView(text("›", 16f, TvStyle.muted).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, 0)
        })
        return entry
    }

    /** 账号卡片：圆形头像 + 昵称 + 登录状态，右侧是登录/管理按钮，整卡可点。 */
    private fun profileCard(): View = AccountUi.profileCard(activity, activity.phoneAccount)

    /** 统计格：上方数字、下方名称，整格可点。
     *  子视图必须用 WRAP_CONTENT 宽度——Vertical LinearLayout 的默认布局参数是 MATCH_PARENT，
     *  那样文字会在整格宽度里左对齐，外面的 CENTER_HORIZONTAL 看起来就失效了。 */
    private fun statCell(value: String, label: String, onClick: () -> Unit): LinearLayout {
        val cell = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }
        cell.addView(text(value, 19f, TvStyle.accent).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, PhoneUi.lp(-2, -2))
        cell.addView(text(label, 12f, TvStyle.muted).apply { gravity = Gravity.CENTER }, PhoneUi.lp(-2, -2).apply { topMargin = dp(3) })
        return cell
    }

    fun mine(scroll: LinearLayout) {
        val library = activity.phoneLibrary
        val favorites = library.favorites()
        val updates = favorites.count { library.favoriteUpdate(it.id)?.hasUpdate == true }
        val later = library.later()

        val head = row().apply { setPadding(dp(18), dp(10), dp(18), dp(4)) }
        head.addView(text("我的", 22f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }, PhoneUi.lp(0, -2).apply { weight = 1f })
        scroll.addView(head, PhoneUi.lp(-1, -2))

        scroll.addView(profileCard(), PhoneUi.lp(-1, -2).apply {
            leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(12)
        })

        // 内容统计：三格等分，点到对应的列表页；不再展示「最近观看」横滑条带
        val stats = row().apply {
            setPadding(0, dp(8), 0, dp(8))
            background = TvStyle.shape(activity, TvStyle.surface, radius = 14)
        }
        val cells = listOf(
            statCell(favorites.size.toString(), "我的收藏") { activity.openFavoritesPhone() },
            statCell(updates.toString(), "有更新") { activity.openCollectionPhone("updates") },
            statCell(later.size.toString(), "稍后看") { activity.openCollectionPhone("later") })
        cells.forEachIndexed { index, cell ->
            if (index > 0) {
                stats.addView(View(activity).apply { setBackgroundColor(TvStyle.outline) },
                    PhoneUi.lp(dp(1), dp(30)).apply { gravity = Gravity.CENTER_VERTICAL })
            }
            stats.addView(cell, PhoneUi.lp(0, -2).apply { weight = 1f })
        }
        scroll.addView(stats, PhoneUi.lp(-1, -2).apply {
            leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(10)
        })

        sectionTitle(scroll, "偏好与数据")
        groupCard(scroll, listOf(
            // 设置入口：竖屏下打开完整设置页（[PhoneSettings]），不再需要转到横屏才能改全
            entryRow("设置", "播放、外观、设备与数据") { activity.showPhoneSettings() },
            entryRow("观看统计", "本机累计观看时长") { activity.phoneWatchStats() },
            // 运行日志：放在「我的」一级页，出问题时少点两步就能复制到最新日志
            entryRow("运行日志", "排查问题用 · 一键复制") { activity.phoneRuntimeLogs() }))

        sectionTitle(scroll, "关于")
        groupCard(scroll, listOf(
            entryRow("版本与更新", "当前版本", BuildConfig.VERSION_NAME) { activity.phoneUpdater() },
            entryRow("开源许可") {
                TvDialog.info(activity, "开源许可", "本原生版以 GPL-3.0 发布。\n内容协议与加密处理移植自 drpys（22261ad）。\n请求签名算法移植自 openhongguo（GPL-3.0），在本机完成计算与匿名设备注册。\nAndroidX Media3 / OkHttp：Apache-2.0\nKotlin：Apache-2.0\n完整源码和许可证见 GitHub：jycao2/hongguoTV。").show()
            }))

        scroll.addView(text("红果 ${BuildConfig.VERSION_NAME} · 收藏与本机记录保存在设备 · 登录后可同步到红果账号", 11f, TvStyle.muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }, PhoneUi.lp(-1, -2))
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
