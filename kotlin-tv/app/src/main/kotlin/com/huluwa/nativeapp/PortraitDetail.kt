// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.hongguotv.core.Detail
import com.hongguotv.core.Series
import com.hongguotv.core.seasonNumber
import java.util.Locale

/** 竖屏详情：渐变海报区、续播与收藏、季导航和六列选集。 */
class PortraitDetail(
    private val activity: MainActivity,
    private val loadCover: (ImageView, String) -> Unit,
) {
    private fun dp(v: Int) = PortraitUi.dp(activity, v)
    private fun text(v: String, size: Float, color: Int = PortraitStyle.text) = PortraitUi.text(activity, v, size, color)
    private fun lp(w: Int = -1, h: Int = -2) = LinearLayout.LayoutParams(w, h)

    private data class PageBinding(
        val frame: View,
        val scroll: ScrollView,
        val update: (Detail) -> Unit,
        val loading: (String?) -> Unit,
    )
    private var pageBinding: PageBinding? = null

    fun captureScrollY(): Int = pageBinding?.scroll?.scrollY ?: 0

    fun restoreScrollY(position: Int) {
        val binding = pageBinding ?: return
        binding.scroll.post {
            if (pageBinding === binding) binding.scroll.scrollTo(0, position.coerceAtLeast(0))
        }
    }

    fun hasPage(root: FrameLayout) = pageBinding?.frame?.parent === root

    fun updateSeason(root: FrameLayout, data: Detail): Boolean {
        val binding = pageBinding?.takeIf { it.frame.parent === root } ?: return false
        binding.update(data)
        return true
    }

    fun setSeasonLoading(root: FrameLayout, seasonId: String?) {
        pageBinding?.takeIf { it.frame.parent === root }?.loading?.invoke(seasonId)
    }

    private data class Playback(
        val currentIndex: Int,
        val watchedEpisodeIds: Set<String>,
        val playIndex: Int,
        val resumePosition: Long,
        val label: String,
    )

    private fun playbackState(data: Detail): Playback {
        val library = activity.phoneLibrary
        val progress = library.progress(data.series.id)
        val watched = library.watched(data.series.id)
        val index = progress?.let { data.episodes.indexOf(it.episodeId) } ?: -1
        val replay = watched || (index >= 0 && progress?.completed == true && index == data.episodes.lastIndex)
        val playIndex = when {
            replay || index < 0 -> 0
            progress?.completed == true -> index + 1
            else -> index
        }
        val position = progress?.takeIf { !watched && !it.completed && index >= 0 }?.position?.coerceAtLeast(0L) ?: 0L
        val label = when {
            data.episodes.isEmpty() -> "暂无可播放剧集"
            replay -> "重新观看"
            index >= 0 -> "继续看第 ${playIndex + 1} 集"
            else -> "开始观看"
        }
        return Playback(index, library.watchedEpisodes(data.series.id), playIndex, position, label)
    }

    private fun metadata(data: Detail) = listOf(
        data.series.tags,
        if (data.episodes.isEmpty()) (if (data.series.release != null) "待上线" else data.series.badge) else "全 ${data.episodes.size} 集",
        data.social.heatText.takeIf { it.isNotBlank() }?.let { "热度 $it" }.orEmpty(),
    ).filter { it.isNotBlank() }.joinToString(" · ")

    fun renderState(root: FrameLayout, label: String, retry: (() -> Unit)? = null) {
        pageBinding = null
        root.removeAllViews()
        activity.phoneStatusBarLight(!PortraitStyle.isDark)
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

    fun render(root: FrameLayout, initial: Detail) {
        pageBinding = null
        var data = initial
        var loadingSeasonId: String? = null
        var playback = playbackState(data)
        val library = activity.phoneLibrary
        activity.phoneStatusBarLight(!PortraitStyle.isDark)
        root.removeAllViews()
        val (frame, body, _) = PortraitUi.scaffold(activity, activity.phoneDetailOriginTab, activity::switchPhoneTab)
        val backdrop = FrameLayout(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(PortraitStyle.heroTop, PortraitStyle.heroMiddle, PortraitStyle.background))
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
                intArrayOf(if (PortraitStyle.isDark) Color.argb(55, 0, 0, 0) else Color.argb(20, 255, 255, 255), PortraitStyle.background))
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

        val topBackground = ColorDrawable(PortraitStyle.background).apply { alpha = 0 }
        val top = PortraitUi.row(activity).apply {
            background = topBackground
            setPadding(dp(18), dp(16), dp(18), dp(20))
        }
        top.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_page_back)
            setColorFilter(PortraitStyle.text)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = PortraitStyle.selectable(activity, PortraitStyle.translucentSurface, radius = 24)
            contentDescription = "返回"
            isFocusable = true
            setOnClickListener { activity.goBackPhone() }
        }, lp(dp(42), dp(42)))
        val titleInBar = text(data.series.title, 16f).apply {
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            alpha = 0f
            visibility = View.INVISIBLE
        }
        top.addView(titleInBar, lp(0, -2).apply { weight = 1f; leftMargin = dp(14) })
        // 顶栏覆盖到状态栏安全区，让两个区域随滚动共用同一背景。
        frame.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        PhoneInsets.listen(top) { topInset, _, _ ->
            top.setPadding(dp(18), dp(16) + topInset, dp(18), dp(20))
        }
        val topFadeDistance = dp(170).coerceAtLeast(1).toFloat()
        scroll.viewTreeObserver.addOnScrollChangedListener {
            val progress = (scroll.scrollY / topFadeDistance).coerceIn(0f, 1f)
            titleInBar.visibility = if (progress > 0f) View.VISIBLE else View.INVISIBLE
            titleInBar.alpha = progress
            topBackground.alpha = (255 * progress).toInt()
            backdrop.translationY = -scroll.scrollY.toFloat()
        }

        val hero = PortraitUi.row(activity).apply {
            gravity = Gravity.TOP
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
        val heroTitle = text(data.series.title, 20f).apply {
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        info.addView(heroTitle, lp())
        val ratingView = text("", 13f, PortraitStyle.muted).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        info.addView(ratingView, lp().apply { topMargin = dp(7) })
        fun renderRating(current: Detail) {
            val rating = current.social.rating
            val available = rating.isFinite() && rating > 0.0 && rating <= 10.0
            ratingView.visibility = if (available) View.VISIBLE else View.GONE
            if (!available) { ratingView.text = ""; return }
            val score = String.format(Locale.CHINA, "★ %.1f 分", rating)
            val count = current.social.ratingCount.takeIf { it.isNotBlank() }?.let { " · $it 人评分" }.orEmpty()
            ratingView.text = SpannableString(score + count).apply {
                setSpan(ForegroundColorSpan(PortraitStyle.accent), 0, score.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), 0, score.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        renderRating(data)
        val metadataView = text(metadata(data), 12f, PortraitStyle.muted).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        info.addView(metadataView, lp().apply { topMargin = dp(7) })
        val releaseView = text("", 13f, PortraitStyle.accent).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        info.addView(releaseView, lp().apply { topMargin = dp(8) })
        fun renderRelease(current: Detail) {
            val schedule = current.series.release.takeIf { current.episodes.isEmpty() }
            releaseView.text = schedule?.label.orEmpty()
            releaseView.visibility = if (schedule != null) View.VISIBLE else View.GONE
        }
        renderRelease(data)
        hero.addView(info, lp(0, -2).apply { weight = 1f })
        content.addView(hero, lp())

        var expanded = false
        val synopsis = text(data.series.description.ifBlank { "暂无简介" }, 14f, PortraitStyle.secondaryText).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(16), dp(18), dp(16), dp(14))
            isFocusable = true
            setOnClickListener {
                expanded = !expanded
                maxLines = if (expanded) Int.MAX_VALUE else 3
            }
        }
        content.addView(synopsis, lp())

        val actions = PortraitUi.row(activity).apply { setPadding(dp(16), 0, dp(16), 0) }
        val play = PortraitUi.row(activity).apply {
            gravity = Gravity.CENTER
            background = PortraitStyle.selectable(activity, PortraitStyle.accent, radius = 28)
            visibility = if (data.episodes.isNotEmpty()) View.VISIBLE else View.GONE
            isEnabled = data.episodes.isNotEmpty()
            alpha = if (isEnabled) 1f else .45f
            contentDescription = playback.label
            setOnClickListener {
                if (loadingSeasonId == null) activity.playEpisodePhone(playback.playIndex, playback.resumePosition)
            }
        }
        play.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_play_arrow)
            setColorFilter(PortraitStyle.onAccent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, lp(dp(22), dp(22)).apply { rightMargin = dp(5) })
        val playText = text(playback.label, 15f, PortraitStyle.onAccent).apply { setTypeface(null, Typeface.BOLD) }
        play.addView(playText, lp(-2, -2))
        actions.addView(play, lp(0, dp(54)).apply { weight = 1f })

        val favoriteIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_phone_star)
            setColorFilter(PortraitStyle.text)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val favoriteLabel = text(if (library.favorite(data.series.id)) "已收藏" else "收藏", 15f)
        val favorite = PortraitUi.row(activity).apply {
            gravity = Gravity.CENTER
            background = PortraitStyle.selectable(activity, PortraitStyle.subtleButton, radius = 28)
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
        actions.addView(favorite, lp(dp(113), dp(54)).apply { leftMargin = if (data.episodes.isNotEmpty()) dp(9) else 0 })
        content.addView(actions, lp())

        val seasonHost = PortraitUi.column(activity).apply { setPadding(dp(16), dp(20), dp(16), dp(4)) }
        content.addView(seasonHost, lp())
        val seasonStrip = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val seasonLine = PortraitUi.row(activity)
        seasonStrip.addView(seasonLine)
        seasonHost.addView(seasonStrip, lp())
        val knownSeasons = linkedMapOf<String, Series>()
        val seasonButtons = linkedMapOf<String, TextView>()
        fun updateSeasonSelection() {
            seasonButtons.forEach { (id, button) ->
                val selected = id == data.series.id
                if (button.isSelected != selected) {
                    button.isSelected = selected
                    button.setTextColor(if (selected) PortraitStyle.onChipSelected else PortraitStyle.text)
                    button.background = PortraitStyle.selectable(activity, if (selected) PortraitStyle.chipSelected else PortraitStyle.raised, radius = 20)
                }
                button.alpha = if (id == loadingSeasonId) .5f else 1f
            }
        }
        fun renderSeasons(current: Detail) {
            if (current.series.id != data.series.id) return
            // 缓存和联网结果都是完整列表；尚未取到分季时才沿用切季前的条目。
            if (current.seasonsLoaded) knownSeasons.clear()
            (listOf(current.series) + current.sameName).forEach { knownSeasons[it.id] = it }
            val seasons = knownSeasons.values.sortedBy { seasonNumber(it.title).takeIf { number -> number != Int.MAX_VALUE } ?: 0 }
            seasonHost.visibility = if (seasons.size > 1) View.VISIBLE else View.GONE
            if (seasons.size <= 1) return
            if (seasonButtons.keys.toList() != seasons.map { it.id }) {
                val oldScrollX = seasonStrip.scrollX
                seasonLine.removeAllViews()
                seasonButtons.clear()
                seasons.forEach { season ->
                    val number = seasonNumber(season.title)
                    val label = if (number == Int.MAX_VALUE) season.title else "第 $number 季"
                    val button = text(label, 13f).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(13), dp(7), dp(13), dp(7))
                        background = PortraitStyle.selectable(activity, PortraitStyle.raised, radius = 20)
                        setOnClickListener {
                            knownSeasons[season.id]?.let { latest ->
                                if (latest.id != data.series.id || loadingSeasonId != null) activity.switchDetailSeasonPhone(latest)
                            }
                        }
                    }
                    seasonButtons[season.id] = button
                    seasonLine.addView(button, lp(-2, -2).apply { rightMargin = dp(6) })
                }
                seasonStrip.post {
                    if (oldScrollX > 0) seasonStrip.scrollTo(oldScrollX, 0)
                    else seasonButtons[data.series.id]?.let { seasonStrip.scrollTo(it.left, 0) }
                }
            }
            seasons.forEach { season ->
                val number = seasonNumber(season.title)
                seasonButtons[season.id]?.apply {
                    text = if (number == Int.MAX_VALUE) season.title else "第 $number 季"
                    contentDescription = listOf(season.title, season.release?.label.orEmpty()).filter { it.isNotBlank() }.joinToString("，")
                }
            }
            updateSeasonSelection()
        }

        fun renderExtras(current: Detail) {
            if (current.series.id != data.series.id) return
            data = current
            renderRating(current)
            metadataView.text = metadata(current)
            renderRelease(current)
            renderSeasons(current)
        }

        val pageSize = 100
        fun totalPages() = ((data.episodes.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        var episodePage = (playback.currentIndex / pageSize).coerceIn(0, totalPages() - 1)
        var episodeColumns = 6
        val episodeHost = PortraitUi.column(activity).apply { setPadding(dp(16), 0, dp(16), 0) }
        content.addView(episodeHost, lp())
        var episodeStatus: TextView? = null
        fun renderEpisodes() {
            episodeHost.removeAllViews()
            val totalPages = totalPages()
            val pager = PortraitUi.row(activity).apply { minimumHeight = dp(48) }
            pager.addView(text("选集", 17f).apply { setTypeface(null, Typeface.BOLD) }, lp(-2, -2))
            val status = text(if (loadingSeasonId != null) "正在切换…" else if (data.episodes.isEmpty() && data.series.release != null) "待上线" else "共 ${data.episodes.size} 集", 12f, PortraitStyle.muted)
            episodeStatus = status
            pager.addView(status, lp(0, -2).apply {
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
                        background = PortraitStyle.selectable(activity, if (page == episodePage) PortraitStyle.selectedSurface else Color.TRANSPARENT, radius = 18)
                        isSelected = page == episodePage
                        setOnClickListener { episodePage = page; renderEpisodes() }
                    }, lp(dp(64), dp(26)))
                }
                pager.addView(ranges, lp(dp(132), dp(30)))
                ranges.post { ranges.scrollTo(episodePage * dp(64), 0) }
            }
            episodeHost.addView(pager, lp())
            if (data.episodes.isEmpty()) {
                episodeHost.addView(PortraitUi.emptyHint(activity,
                    if (data.series.release != null) "上线后即可观看，可先收藏" else "暂时没有可看的剧集"), lp())
                return
            }
            val pageStart = episodePage * pageSize
            val pageEnd = minOf(pageStart + pageSize, data.episodes.size)
            (pageStart until pageEnd).chunked(episodeColumns).forEach { numbers ->
                val line = PortraitUi.row(activity)
                numbers.forEachIndexed { column, index ->
                    val selected = index == playback.currentIndex
                    val seen = data.episodes[index] in playback.watchedEpisodeIds
                    val cell = FrameLayout(activity).apply {
                        background = PortraitStyle.selectable(activity, if (selected) PortraitStyle.accent else PortraitStyle.raised, radius = 9)
                        isSelected = selected
                        contentDescription = "第 ${index + 1} 集" + if (seen) "，已看过" else ""
                        setOnClickListener {
                            if (loadingSeasonId == null) activity.playEpisodePhone(index, if (index == playback.currentIndex) playback.resumePosition else 0L)
                        }
                    }
                    cell.addView(text("${index + 1}", 15f,
                        if (selected) PortraitStyle.onAccent else if (seen) PortraitStyle.muted else PortraitStyle.text).apply {
                        gravity = Gravity.CENTER
                    }, FrameLayout.LayoutParams(-1, -1))
                    if (seen) cell.addView(text("✓", 9f, if (selected) PortraitStyle.onAccent else PortraitStyle.accent),
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

        fun setLoading(seasonId: String?) {
            loadingSeasonId = seasonId
            play.visibility = if (data.episodes.isNotEmpty()) View.VISIBLE else View.GONE
            favorite.layoutParams = (favorite.layoutParams as LinearLayout.LayoutParams).apply {
                leftMargin = if (data.episodes.isNotEmpty()) dp(9) else 0
            }
            play.isEnabled = seasonId == null && data.episodes.isNotEmpty()
            play.alpha = if (play.isEnabled) 1f else .45f
            episodeStatus?.text = if (seasonId != null) "正在切换…" else if (data.episodes.isEmpty() && data.series.release != null) "待上线" else "共 ${data.episodes.size} 集"
            updateSeasonSelection()
        }
        pageBinding = PageBinding(frame, scroll, { next ->
            val previousCover = data.series.cover
            val oldScrollY = scroll.scrollY
            val oldSeasonTop = seasonHost.top
            data = next
            playback = playbackState(next)
            loadingSeasonId = null
            titleInBar.text = next.series.title
            heroTitle.text = next.series.title
            renderRating(next)
            metadataView.text = metadata(next)
            renderRelease(next)
            synopsis.text = next.series.description.ifBlank { "暂无简介" }
            playText.text = playback.label
            play.contentDescription = playback.label
            val saved = library.favorite(next.series.id)
            favoriteLabel.text = if (saved) "已收藏" else "收藏"
            favoriteIcon.alpha = if (saved) 1f else .45f
            favorite.isSelected = saved
            favorite.contentDescription = favoriteLabel.text
            if (next.series.cover != previousCover) {
                loadCover(poster, next.series.cover)
                loadCover(backdropCover, next.series.cover)
            }
            episodePage = (playback.currentIndex / pageSize).coerceIn(0, totalPages() - 1)
            renderSeasons(next)
            renderEpisodes()
            setLoading(null)
            // 上方标题或简介高度变化时，维持季导航在屏幕中的位置。
            val observer = content.viewTreeObserver
            observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                    if (frame.parent === root && data.series.id == next.series.id && oldScrollY > 0) {
                        scroll.scrollTo(0, (oldScrollY + seasonHost.top - oldSeasonTop).coerceAtLeast(0))
                    }
                    return true
                }
            })
            activity.loadDetailExtras(next, ::renderExtras)
        }, ::setLoading)
        frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (pageBinding?.frame === v) pageBinding = null
            }
        })
        renderSeasons(data)
        activity.loadDetailExtras(data, ::renderExtras)
    }
}
