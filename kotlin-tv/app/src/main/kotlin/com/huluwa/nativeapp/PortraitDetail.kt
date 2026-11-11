// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.hongguotv.core.Detail
import com.hongguotv.core.seasonNumber

/** 竖屏详情：渐变海报区、续播与收藏、季导航和六列选集。 */
class PortraitDetail(
    private val activity: MainActivity,
    private val loadCover: (ImageView, String) -> Unit,
) {
    private fun dp(v: Int) = PortraitUi.dp(activity, v)
    private fun text(v: String, size: Float, color: Int = PortraitStyle.text) = PortraitUi.text(activity, v, size, color)
    private fun lp(w: Int = -1, h: Int = -2) = LinearLayout.LayoutParams(w, h)

    fun renderState(root: FrameLayout, label: String, retry: (() -> Unit)? = null) {
        root.removeAllViews()
        activity.phoneStatusBarLight(true)
        val (frame, body, _) = PortraitUi.scaffold(activity, activity.phoneDetailOriginTab, activity::switchPhoneTab)
        val back = ImageView(activity).apply {
            setImageResource(R.drawable.ic_page_back)
            setColorFilter(PortraitStyle.text)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            contentDescription = "返回"
            setOnClickListener { activity.goBackPhone() }
        }
        body.addView(back, lp(dp(48), dp(48)).apply { leftMargin = dp(12); topMargin = dp(12) })
        val state = PortraitUi.column(activity).apply { gravity = Gravity.CENTER }
        if (retry == null) state.addView(ProgressBar(activity).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(PortraitStyle.accent)
        }, lp(dp(30), dp(30)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        state.addView(PortraitUi.messageBlock(activity, label, retry), lp(-2, -2))
        body.addView(state, lp(-1, 0).apply { weight = 1f; bottomMargin = dp(90) })
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
    }

    fun render(root: FrameLayout, data: Detail) {
        val library = activity.phoneLibrary
        activity.phoneStatusBarLight(true)
        root.removeAllViews()
        val (frame, body, _) = PortraitUi.scaffold(activity, activity.phoneDetailOriginTab, activity::switchPhoneTab)
        val backdrop = FrameLayout(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(PortraitStyle.heroTop, PortraitStyle.heroMiddle, Color.WHITE))
        }
        val backdropCover = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            alpha = if (Build.VERSION.SDK_INT >= 31) .75f else .12f
            if (Build.VERSION.SDK_INT >= 31) setRenderEffect(
                RenderEffect.createBlurEffect(dp(45).toFloat(), dp(45).toFloat(), Shader.TileMode.CLAMP))
        }
        backdrop.addView(backdropCover, FrameLayout.LayoutParams(-1, -1))
        loadCover(backdropCover, data.series.cover)
        backdrop.addView(View(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(20, 255, 255, 255), Color.WHITE))
        }, FrameLayout.LayoutParams(-1, -1))
        frame.addView(backdrop, 0, FrameLayout.LayoutParams(-1, dp(340)))
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        val content = PortraitUi.column(activity)
        scroll.addView(content)
        val page = FrameLayout(activity)
        page.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        body.addView(page, lp(-1, -1))
        content.setPadding(0, dp(78), 0, 0)
        PhoneInsets.listen(scroll) { _, bottomInset, _ -> scroll.setPadding(0, 0, 0, dp(92) + bottomInset) }

        val top = PortraitUi.row(activity).apply { setPadding(dp(18), dp(16), dp(18), dp(20)) }
        top.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_page_back)
            setColorFilter(PortraitStyle.text)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = PortraitStyle.selectable(activity, Color.argb(145, 255, 255, 255), radius = 24)
            contentDescription = "返回"
            isFocusable = true
            setOnClickListener { activity.goBackPhone() }
        }, lp(dp(42), dp(42)))
        val titleInBar = text(data.series.title, 16f).apply {
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.INVISIBLE
        }
        top.addView(titleInBar, lp(0, -2).apply { weight = 1f; leftMargin = dp(14) })
        // 顶栏覆盖到状态栏安全区，让两个区域随滚动共用同一背景。
        frame.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        PhoneInsets.listen(top) { topInset, _, _ ->
            top.setPadding(dp(18), dp(16) + topInset, dp(18), dp(20))
        }
        scroll.viewTreeObserver.addOnScrollChangedListener {
            val pastHero = scroll.scrollY > dp(170)
            titleInBar.visibility = if (pastHero) View.VISIBLE else View.INVISIBLE
            top.setBackgroundColor(if (pastHero) Color.WHITE else Color.TRANSPARENT)
            backdrop.translationY = -scroll.scrollY.toFloat()
        }

        val hero = PortraitUi.row(activity).apply {
            gravity = Gravity.BOTTOM
            setPadding(dp(16), 0, dp(16), 0)
        }
        val poster = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = PortraitStyle.shape(activity, PortraitStyle.raised, radius = 10)
            clipToOutline = true
            elevation = dp(3).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        loadCover(poster, data.series.cover)
        hero.addView(poster, lp(dp(118), dp(157)).apply { rightMargin = dp(16) })
        val info = PortraitUi.column(activity)
        info.addView(text(data.series.title, 20f).apply {
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(3).toFloat(), 1f)
        }, lp())
        val metadata = listOf(data.series.tags, if (data.episodes.isEmpty()) data.series.badge else "全 ${data.episodes.size} 集",
            data.social.heatText.takeIf { it.isNotBlank() }?.let { "热度 $it" }.orEmpty())
            .filter { it.isNotBlank() }.joinToString(" · ")
        info.addView(text(metadata, 12f, PortraitStyle.muted).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(3).toFloat(), 1f)
        }, lp().apply { topMargin = dp(7) })
        hero.addView(info, lp(0, -2).apply { weight = 1f })
        content.addView(hero, lp())

        var expanded = false
        content.addView(text(data.series.description.ifBlank { "暂无简介" }, 14f, Color.rgb(60, 60, 60)).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(16), dp(18), dp(16), dp(14))
            isFocusable = true
            setOnClickListener {
                expanded = !expanded
                maxLines = if (expanded) Int.MAX_VALUE else 3
            }
        }, lp())

        val progress = library.progress(data.series.id)
        val watched = library.watched(data.series.id)
        val resumeIndex = progress?.let { data.episodes.indexOf(it.episodeId) } ?: -1
        val replay = watched || (resumeIndex >= 0 && progress?.completed == true && resumeIndex == data.episodes.lastIndex)
        val playIndex = when {
            replay || resumeIndex < 0 -> 0
            progress?.completed == true -> resumeIndex + 1
            else -> resumeIndex
        }
        val resumePosition = progress?.takeIf { !watched && !it.completed && resumeIndex >= 0 }
            ?.position?.coerceAtLeast(0L) ?: 0L
        val playLabel = when {
            data.episodes.isEmpty() -> "暂无可播放剧集"
            replay -> "重新观看"
            resumeIndex >= 0 -> "继续看第 ${playIndex + 1} 集"
            else -> "开始观看"
        }
        val actions = PortraitUi.row(activity).apply { setPadding(dp(16), 0, dp(16), 0) }
        val play = PortraitUi.row(activity).apply {
            gravity = Gravity.CENTER
            background = PortraitStyle.selectable(activity, PortraitStyle.accent, radius = 28)
            isEnabled = data.episodes.isNotEmpty()
            alpha = if (isEnabled) 1f else .45f
            contentDescription = playLabel
            setOnClickListener {
                activity.playEpisodePhone(playIndex, resumePosition)
            }
        }
        play.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_play_arrow)
            setColorFilter(Color.WHITE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, lp(dp(22), dp(22)).apply { rightMargin = dp(5) })
        play.addView(text(playLabel, 15f, Color.WHITE).apply { setTypeface(null, Typeface.BOLD) }, lp(-2, -2))
        actions.addView(play, lp(0, dp(54)).apply { weight = 1f })

        val favoriteIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_phone_star)
            setColorFilter(PortraitStyle.text)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val favoriteLabel = text(if (library.favorite(data.series.id)) "已收藏" else "收藏", 15f)
        val favorite = PortraitUi.row(activity).apply {
            gravity = Gravity.CENTER
            background = PortraitStyle.selectable(activity, Color.rgb(215, 215, 215), radius = 28)
            isSelected = library.favorite(data.series.id)
            contentDescription = favoriteLabel.text
            setOnClickListener {
                activity.phoneFavoriteMonitor.stop()
                val saved = library.toggle(data.series)
                if (saved && data.episodes.isNotEmpty()) {
                    library.observeFavorite(data.series.id, data.episodes.size, System.currentTimeMillis(), acknowledge = true)
                }
                favoriteLabel.text = if (saved) "已收藏" else "收藏"
                favoriteIcon.alpha = if (saved) 1f else .45f
                isSelected = saved
                contentDescription = favoriteLabel.text
            }
        }
        favoriteIcon.alpha = if (library.favorite(data.series.id)) 1f else .45f
        favorite.addView(favoriteIcon, lp(dp(22), dp(22)).apply { rightMargin = dp(6) })
        favorite.addView(favoriteLabel, lp(-2, -2))
        actions.addView(favorite, lp(dp(113), dp(54)).apply { leftMargin = dp(9) })
        content.addView(actions, lp())

        val seasonHost = PortraitUi.column(activity).apply { setPadding(dp(16), dp(20), dp(16), dp(4)) }
        content.addView(seasonHost, lp())
        fun renderSeasons(current: Detail) {
            seasonHost.removeAllViews()
            val seasons = (listOf(current.series) + current.sameName).distinctBy { it.id }
                .sortedBy { seasonNumber(it.title).takeIf { number -> number != Int.MAX_VALUE } ?: 0 }
            seasonHost.visibility = if (seasons.size > 1) View.VISIBLE else View.GONE
            if (seasons.size <= 1) return
            val strip = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
            val line = PortraitUi.row(activity)
            strip.addView(line)
            seasons.forEach { season ->
                val selected = season.id == current.series.id
                val number = seasonNumber(season.title)
                val label = if (number == Int.MAX_VALUE) season.title else "第 $number 季"
                line.addView(text(label, 13f, if (selected) Color.WHITE else PortraitStyle.text).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(13), dp(7), dp(13), dp(7))
                    background = PortraitStyle.selectable(activity, if (selected) Color.BLACK else PortraitStyle.raised, radius = 20)
                    isSelected = selected
                    if (selected) post { strip.scrollTo(left, 0) }
                    setOnClickListener { if (!selected) activity.openDetailPhone(season) }
                }, lp(-2, -2).apply { rightMargin = dp(6) })
            }
            seasonHost.addView(strip, lp())
        }

        val pageSize = 100
        val totalPages = ((data.episodes.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val currentIndex = resumeIndex
        var episodePage = (currentIndex / pageSize).coerceIn(0, totalPages - 1)
        var episodeColumns = 6
        val episodeHost = PortraitUi.column(activity).apply { setPadding(dp(16), 0, dp(16), 0) }
        content.addView(episodeHost, lp())
        fun renderEpisodes() {
            episodeHost.removeAllViews()
            val pager = PortraitUi.row(activity).apply { minimumHeight = dp(48) }
            pager.addView(text("选集", 17f).apply { setTypeface(null, Typeface.BOLD) }, lp(-2, -2))
            pager.addView(text("共 ${data.episodes.size} 集", 12f, PortraitStyle.muted), lp(0, -2).apply {
                weight = 1f
                leftMargin = dp(8)
            })
            if (totalPages > 1) {
                val ranges = HorizontalScrollView(activity).apply {
                    isHorizontalScrollBarEnabled = false
                    background = PortraitStyle.shape(activity, PortraitStyle.raised, radius = 20)
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                }
                val rangeRow = PortraitUi.row(activity)
                ranges.addView(rangeRow)
                repeat(totalPages) { page ->
                    val start = page * pageSize + 1
                    val end = minOf((page + 1) * pageSize, data.episodes.size)
                    rangeRow.addView(text("$start–$end", 12f).apply {
                        gravity = Gravity.CENTER
                        background = PortraitStyle.selectable(activity, if (page == episodePage) Color.WHITE else Color.TRANSPARENT, radius = 18)
                        isSelected = page == episodePage
                        setOnClickListener { episodePage = page; renderEpisodes() }
                    }, lp(dp(64), dp(26)))
                }
                pager.addView(ranges, lp(dp(132), dp(30)))
                ranges.post { ranges.scrollTo(episodePage * dp(64), 0) }
            }
            episodeHost.addView(pager, lp())
            if (data.episodes.isEmpty()) {
                episodeHost.addView(PortraitUi.emptyHint(activity, "暂无剧集"), lp())
                return
            }
            val pageStart = episodePage * pageSize
            val pageEnd = minOf(pageStart + pageSize, data.episodes.size)
            (pageStart until pageEnd).chunked(episodeColumns).forEach { numbers ->
                val line = PortraitUi.row(activity)
                numbers.forEachIndexed { column, index ->
                    val selected = index == currentIndex
                    val seen = watched || (progress?.completed == true && index == currentIndex)
                    val cell = FrameLayout(activity).apply {
                        background = PortraitStyle.selectable(activity, if (selected) PortraitStyle.accent else PortraitStyle.raised, radius = 9)
                        isSelected = selected
                        contentDescription = "第 ${index + 1} 集" + if (seen) "，已看过" else ""
                        setOnClickListener {
                            activity.playEpisodePhone(index, if (index == currentIndex) resumePosition else 0L)
                        }
                    }
                    cell.addView(text("${index + 1}", 15f,
                        if (selected) Color.WHITE else if (seen) PortraitStyle.muted else PortraitStyle.text).apply {
                        gravity = Gravity.CENTER
                    }, FrameLayout.LayoutParams(-1, -1))
                    if (seen) cell.addView(text("✓", 9f, if (selected) Color.WHITE else PortraitStyle.accent),
                        FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(3); rightMargin = dp(4) })
                    line.addView(cell, lp(0, dp(40)).apply {
                        weight = 1f
                        if (column < episodeColumns - 1) rightMargin = dp(8)
                    })
                }
                repeat(episodeColumns - numbers.size) { column ->
                    line.addView(View(activity), lp(0, dp(40)).apply {
                        weight = 1f
                        if (numbers.size + column < episodeColumns - 1) rightMargin = dp(8)
                    })
                }
                episodeHost.addView(line, lp().apply { bottomMargin = dp(8) })
            }
        }
        episodeHost.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val availableWidth = right - left - episodeHost.paddingLeft - episodeHost.paddingRight
            val columns = ((availableWidth + dp(8)) / dp(58)).coerceIn(1, 6)
            if (columns != episodeColumns) {
                episodeColumns = columns
                episodeHost.post { renderEpisodes() }
            }
        }
        renderEpisodes()

        renderSeasons(data)
        activity.loadDetailExtras(data, ::renderSeasons)
    }
}
