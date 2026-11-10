// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hongguotv.core.Detail
import com.hongguotv.core.Series
import com.hongguotv.core.seasonNumber
import java.util.Locale

/**
 * 手机竖屏详情页：顶部大海报区 + 标题/评分 + 操作按钮 + 简介展开 + 集数分页网格 + 推荐。
 * 与 TV 版 showDetail 并行存在，互不干扰；播放走 MainActivity 原有 playEpisode
 * （横竖屏切换时由 onConfigurationChanged 重建，播放本身不中断语义不变）。
 */
class PhoneDetail(
    private val activity: MainActivity,
    private val loadCover: (ImageView, String) -> Unit,
) {
    private fun dp(v: Int) = PhoneUi.dp(activity, v)
    private fun text(v: String, size: Float, color: Int = TvStyle.text) = PhoneUi.text(activity, v, size, color)
    private fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(w, h)

    fun render(root: android.widget.FrameLayout, data: Detail) {
        val library = activity.library
        root.removeAllViews()
        root.setBackgroundColor(TvStyle.background)
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
        val content = PhoneUi.column(activity)
        scroll.addView(content)
        root.addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1))

        // 顶部返回条
        val top = PhoneUi.row(activity).apply { setPadding(dp(10), dp(10), dp(10), dp(4)) }
        val back = text("‹ 返回", 15f).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = TvStyle.shape(activity, TvStyle.raised)
            setOnClickListener { activity.goBackPhone() }
        }
        top.addView(back)
        content.addView(top, lp(-1, -2))

        // 海报 + 信息区
        val hero = PhoneUi.row(activity).apply { gravity = Gravity.TOP; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        val poster = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        loadCover(poster, data.series.cover)
        hero.addView(poster, lp(dp(110), dp(157)).apply { rightMargin = dp(14) })
        val info = PhoneUi.column(activity)
        info.addView(text(data.series.title, 20f).apply { setTypeface(null, Typeface.BOLD); maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
        info.addView(text("${data.episodes.size} 集  ·  ${data.series.tags}", 12f, TvStyle.muted).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(7), 0, dp(7))
        })
        val progress = library.progress(data.series.id)
        progress?.let { info.addView(text(if (it.completed) "整剧已看完" else "看到第 ${it.episodeIndex+1} 集", 12f, TvStyle.gold)) }
        if (!data.social.isEmpty) {
            val metrics = mutableListOf<String>()
            if (data.social.rating > 0) metrics += String.format(Locale.CHINA, "%.1f 分", data.social.rating)
            if (data.social.favoriteText.isNotBlank()) metrics += data.social.favoriteText
            if (data.social.heatText.isNotBlank()) metrics += data.social.heatText
            info.addView(text(metrics.joinToString("  ·  "), 12f, TvStyle.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, 0, 0, dp(6)) })
        }
        hero.addView(info, lp(0, -2).apply { weight = 1f })
        content.addView(hero, lp(-1, -2))

        // 操作按钮：播放 / 收藏
        val resumeIndex = progress?.let { data.episodes.indexOf(it.episodeId).takeIf { n -> n >= 0 } } ?: 0
        val watched = library.watched(data.series.id)
        val actions = PhoneUi.row(activity).apply { setPadding(dp(14), dp(4), dp(14), dp(4)) }
        val playLabel = when {
            watched -> "重新观看"
            progress != null && !progress.completed -> "继续第 ${resumeIndex+1} 集"
            else -> "开始观看"
        }
        val play = text(playLabel, 16f, TvStyle.background).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = TvStyle.shape(activity, TvStyle.accent)
            setOnClickListener {
                activity.playEpisodePhone(if (watched) 0 else if (progress?.completed == true) (resumeIndex+1).coerceAtMost(data.episodes.lastIndex) else resumeIndex,
                    if (watched) 0 else progress?.takeIf { !it.completed && resumeIndex==it.episodeIndex }?.position ?: 0)
            }
        }
        actions.addView(play, lp(0, -2).apply { weight = 1f })
        lateinit var favorite: TextView
        favorite = text(if (library.favorite(data.series.id)) "已收藏" else "收藏", 15f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = TvStyle.shape(activity, TvStyle.raised)
            setOnClickListener {
                activity.favoriteMonitor.stop()
                val saved = library.toggle(data.series)
                if (saved) library.observeFavorite(data.series.id, data.episodes.size, System.currentTimeMillis(), acknowledge = true)
                favorite.text = if (saved) "已收藏" else "收藏"
            }
        }
        actions.addView(favorite, lp(0, -2).apply { weight = 1f; leftMargin = dp(10) })
        content.addView(actions, lp(-1, -2))

        // 简介（可展开）
        val description = data.series.description.ifBlank { "选择剧集开始观看" }
        var expanded = false
        lateinit var synopsis: TextView
        lateinit var toggle: TextView
        synopsis = text(description, 14f, TvStyle.muted).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(14), dp(10), dp(14), dp(2)) }
        toggle = text(if (expanded) "收起简介" else "展开简介", 13f, TvStyle.muted).apply {
            setPadding(dp(14), dp(4), dp(14), dp(4))
            setOnClickListener {
                expanded = !expanded
                synopsis.maxLines = if (expanded) 30 else 3
                toggle.text = if (expanded) "收起简介" else "展开简介"
            }
        }
        content.addView(synopsis, lp(-1, -2))
        content.addView(toggle, lp(-2, -2))

        // 系列剧季导航
        val seasons = (listOf(data.series) + data.sameName).distinctBy { it.id }
            .sortedBy { val number = seasonNumber(it.title); if (number == Int.MAX_VALUE) 0 else number }
        if (seasons.size > 1) {
            val seasonRow = PhoneUi.row(activity).apply { setPadding(dp(14), dp(8), dp(14), dp(2)) }
            seasonRow.addView(text("系列 ${seasons.size} 季", 13f, TvStyle.accent))
            val strip = android.widget.HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
            val line = PhoneUi.row(activity)
            strip.addView(line)
            seasons.forEach { season ->
                val number = seasonNumber(season.title)
                val title = if (number == Int.MAX_VALUE) season.title.take(12) else "第 ${number} 季"
                val chip = text(title, 13f, if (season.id == data.series.id) TvStyle.background else TvStyle.text).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    background = TvStyle.shape(activity, if (season.id == data.series.id) TvStyle.accent else TvStyle.raised)
                    setOnClickListener { if (season.id != data.series.id) activity.openDetailPhone(season) }
                }
                line.addView(chip, lp(-2, -2).apply { rightMargin = dp(10) })
            }
            seasonRow.addView(strip, lp(0, -2).apply { weight = 1f })
            content.addView(seasonRow, lp(-1, -2))
        }

        // 集数网格：每页 5 列 × 6 行 = 30 集；已看降透明度、当前集高亮
        val episodeCols = 5
        val pageSize = episodeCols * 6
        val totalPages = ((data.episodes.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        var episodePage = (activity.phoneEpisodeIndex / pageSize).coerceIn(0, totalPages - 1)
        fun renderEpisodes() {
            val host = content.findViewWithTag<View>("phone-episodes") as? LinearLayout ?: return
            host.removeAllViews()
            val pager = PhoneUi.row(activity).apply { setPadding(dp(14), dp(6), dp(14), dp(4)) }
            pager.addView(text("集数", 14f).apply { setTypeface(null, Typeface.BOLD) }, lp(0, -2).apply { weight = 1f })
            pager.addView(text("${episodePage+1}/$totalPages", 12f, TvStyle.muted).apply { setPadding(dp(8), 0, dp(8), 0) })
            if (episodePage > 0) {
                pager.addView(text("‹", 15f).apply {
                    gravity = Gravity.CENTER; setPadding(dp(12), dp(6), dp(12), dp(6))
                    background = TvStyle.shape(activity, TvStyle.raised)
                    setOnClickListener { episodePage--; renderEpisodes() }
                })
            }
            if (episodePage < totalPages - 1) {
                pager.addView(text("›", 15f).apply {
                    gravity = Gravity.CENTER; setPadding(dp(12), dp(6), dp(12), dp(6))
                    background = TvStyle.shape(activity, TvStyle.raised)
                    setOnClickListener { episodePage++; renderEpisodes() }
                })
            }
            host.addView(pager, lp(-1, -2))
            val pageStart = episodePage * pageSize
            val pageEnd = minOf(pageStart + pageSize, data.episodes.size)
            (pageStart until pageEnd).chunked(episodeCols).forEach { numbers ->
                val line = PhoneUi.row(activity)
                numbers.forEach { index ->
                    val seen = watched || index < activity.phoneEpisodeIndex
                    val item = text("${index+1}", 14f, if (index == activity.phoneEpisodeIndex) TvStyle.background else TvStyle.text).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(11), 0, dp(11))
                        background = TvStyle.shape(activity, if (index == activity.phoneEpisodeIndex) TvStyle.accent else TvStyle.raised)
                        setOnClickListener { activity.playEpisodePhone(index, if (progress?.episodeId == data.episodes[index] && !progress.completed) progress.position else 0) }
                    }
                    if (seen) item.alpha = .45f
                    line.addView(item, lp(0, -2).apply { weight = 1f; rightMargin = dp(8) })
                }
                repeat(episodeCols - numbers.size) { line.addView(View(activity), lp(0, -2).apply { weight = 1f; rightMargin = dp(8) }) }
                host.addView(line, lp(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
        val episodeHost = PhoneUi.column(activity).apply { tag = "phone-episodes" }
        content.addView(episodeHost, lp(-1, -2))
        renderEpisodes()

        // 相关推荐
        if (data.related.isNotEmpty()) {
            content.addView(PhoneUi.shelf(activity, "相关推荐", data.related.take(10), loadCover, { activity.openDetailPhone(it) }), lp(-1, -2).apply { topMargin = dp(6) })
        }
    }
}
