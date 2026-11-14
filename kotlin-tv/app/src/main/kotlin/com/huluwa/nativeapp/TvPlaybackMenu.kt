// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 电视播放页底部菜单：上排操作卡片，下排按需创建的横向选集列表。 */
class TvPlaybackMenu(
    context: Context,
    private val currentEpisode: Int,
    private val episodeCount: Int,
    onNext: () -> Unit,
    onPlay: () -> Unit,
    onSpeed: (TextView) -> Unit,
    onQuality: (TextView) -> Unit,
    onDanmaku: (TextView) -> Unit,
    onMore: (TextView) -> Unit,
    private val onEpisode: (Int) -> Unit,
) : LinearLayout(context) {
    private val compact = resources.displayMetrics.widthPixels / resources.displayMetrics.density < 700
    private val cards = mutableListOf<MenuCard>()
    private val cardRow = LinearLayout(context)
    private val episodeLayout = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
    private val episodeList = RecyclerView(context)
    private var lastCard = 0
    private var focusedEpisode = currentEpisode
    private var pendingEpisode: Int? = null
    private val next: MenuCard
    private val speed: MenuCard
    private val quality: MenuCard
    private val danmaku: MenuCard
    private val play: MenuCard
    val playButton: TextView get() = play

    init {
        orientation = VERTICAL
        val cardScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            clipToPadding = false
            addView(cardRow)
        }
        addView(cardScroll, LayoutParams(-1, -2))
        next = card("下一集", if (currentEpisode + 1 < episodeCount) "第 ${currentEpisode + 2} 集" else "已是最后一集", wide = true) { onNext() }.apply {
            isEnabled = currentEpisode + 1 < episodeCount
            isFocusable = isEnabled
            alpha = if (isEnabled) 1f else .4f
        }
        speed = card("倍速", "") { onSpeed(it) }
        quality = card("清晰度", "") { onQuality(it) }
        danmaku = card("弹幕", "") { onDanmaku(it) }
        play = card("暂停", "") { onPlay() }
        card("更多", "播放设置") { onMore(it) }
        val enabledCards = cards.filter { it.isEnabled }
        enabledCards.forEachIndexed { index, view ->
            view.nextFocusLeftId = enabledCards.getOrNull(index - 1)?.id ?: view.id
            view.nextFocusRightId = enabledCards.getOrNull(index + 1)?.id ?: view.id
            view.nextFocusUpId = view.id
        }
        addView(TextView(context).apply {
            text = "选集   ·   第 ${currentEpisode + 1} 集 / 共 $episodeCount 集"
            textSize = if (compact) 14f else 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            includeFontPadding = false
            setPadding(0, dp(16), 0, dp(10))
        }, LayoutParams(-1, -2))
        episodeList.apply {
            layoutManager = episodeLayout
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null
            adapter = object : RecyclerView.Adapter<EpisodeHolder>() {
                init { setHasStableIds(true) }
                override fun getItemCount() = episodeCount
                override fun getItemId(position: Int) = position.toLong()
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = EpisodeHolder(TextView(context).apply {
                    id = View.generateViewId()
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    textSize = if (compact) 19f else 22f
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    TouchControls.focusOnTouch(this)
                    layoutParams = RecyclerView.LayoutParams(dp(if (compact) 64 else 76), -1).apply { rightMargin = dp(8) }
                })
                override fun onBindViewHolder(holder: EpisodeHolder, position: Int) = holder.bind(position)
            }
            addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    if (pendingEpisode == getChildAdapterPosition(view)) {
                        pendingEpisode = null
                        view.requestFocus()
                    }
                }
                override fun onChildViewDetachedFromWindow(view: View) = Unit
            })
        }
        addView(episodeList, LayoutParams(-1, dp(if (compact) 54 else 62)))
        episodeLayout.scrollToPositionWithOffset((currentEpisode - 2).coerceAtLeast(0), 0)
    }

    fun update(speedLabel: String, qualityLabel: String, danmakuOn: Boolean, playing: Boolean) {
        speed.update("倍速", speedLabel)
        quality.update("清晰度", qualityLabel)
        danmaku.update("弹幕", if (danmakuOn) "已开启" else "已关闭")
        play.update(if (playing) "暂停" else "播放", if (playing) "正在播放" else "已暂停")
    }

    fun focusPrimary() {
        (if (next.isEnabled) next else speed).requestFocus()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun surface(focused: Boolean, current: Boolean = false) = GradientDrawable().apply {
        setColor(if (focused) Color.rgb(223, 249, 255) else if (current) Color.argb(210, 28, 66, 80) else Color.argb(190, 37, 42, 52))
        cornerRadius = dp(4).toFloat()
        setStroke(dp(1), if (focused) Color.WHITE else if (current) Color.rgb(81, 212, 232) else Color.argb(24, 255, 255, 255))
    }

    private fun card(title: String, subtitle: String, wide: Boolean = false, action: (TextView) -> Unit): MenuCard {
        val index = cards.size
        val view = MenuCard(title, subtitle).apply {
            setOnClickListener { action(this) }
            setOnFocusChangeListener { _, focused ->
                paint()
                if (focused) lastCard = index
            }
            setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_DPAD_DOWN && episodeCount > 0) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) focusEpisode(focusedEpisode)
                    true
                } else false
            }
        }
        cards += view
        cardRow.addView(view, LayoutParams(dp(if (wide) { if (compact) 150 else 168 } else { if (compact) 108 else 124 }), dp(if (compact) 66 else 76)).apply {
            if (index > 0) leftMargin = dp(8)
        })
        return view
    }

    private fun focusEpisode(index: Int) {
        if (index !in 0 until episodeCount) return
        pendingEpisode = index
        val existing = episodeLayout.findViewByPosition(index)
        if (existing != null) {
            pendingEpisode = null
            existing.requestFocus()
        } else {
            episodeLayout.scrollToPositionWithOffset(index, 0)
            episodeList.post {
                if (pendingEpisode == index) episodeLayout.findViewByPosition(index)?.let {
                    pendingEpisode = null
                    it.requestFocus()
                }
            }
        }
    }

    private inner class MenuCard(private var title: String, private var subtitle: String) : TextView(this@TvPlaybackMenu.context) {
        init {
            id = View.generateViewId()
            // 复用现有选择器时由菜单统一刷新卡片，避免选择器把双行卡片改成单行文案。
            tag = "playback-settings"
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            TouchControls.focusOnTouch(this)
            gravity = Gravity.CENTER_VERTICAL
            textSize = if (compact) 16f else 18f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(dp(14), dp(8), dp(12), dp(8))
            paint()
        }
        fun update(nextTitle: String, nextSubtitle: String) {
            if (title == nextTitle && subtitle == nextSubtitle) return
            title = nextTitle
            subtitle = nextSubtitle
            paint()
        }
        fun paint() {
            background = surface(isFocused)
            setTextColor(if (isFocused) Color.rgb(17, 36, 51) else Color.WHITE)
            text = SpannableString("$title\n$subtitle").apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (subtitle.isNotEmpty()) setSpan(RelativeSizeSpan(.7f), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            contentDescription = "$title，$subtitle"
        }
    }

    private inner class EpisodeHolder(private val cell: TextView) : RecyclerView.ViewHolder(cell) {
        fun bind(index: Int) {
            val current = index == currentEpisode
            fun paint() {
                cell.background = surface(cell.isFocused, current)
                cell.setTextColor(if (cell.isFocused) Color.rgb(17, 36, 51) else if (current) Color.rgb(81, 224, 243) else Color.WHITE)
                cell.setTypeface(null, if (current || cell.isFocused) Typeface.BOLD else Typeface.NORMAL)
            }
            cell.text = if (current) SpannableString("${index + 1}\n播放中").apply {
                setSpan(RelativeSizeSpan(.48f), (index + 1).toString().length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else "${index + 1}"
            cell.contentDescription = "第 ${index + 1} 集" + if (current) "，当前播放" else ""
            cell.setOnFocusChangeListener { _, focused ->
                paint()
                if (focused) focusedEpisode = index
            }
            cell.setOnClickListener { onEpisode(index) }
            cell.setOnKeyListener { _, key, event ->
                when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) cards[lastCard].requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) focusEpisode(index + if (key == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> true
                    else -> false
                }
            }
            paint()
        }
    }
}
