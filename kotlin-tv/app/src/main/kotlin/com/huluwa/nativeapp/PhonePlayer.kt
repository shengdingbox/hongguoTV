// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.hongguotv.core.Detail

/**
 * 手机竖屏播放页，交互参考葫芦娃 App 的红果播放页：
 *
 *  - 视频占满整屏（不再固定 16:9 + 下方常驻面板），单击显隐控件、双击播放/暂停
 *  - 上下滑动切换上一集/下一集（累计位移或快速轻扫触发）
 *  - 顶部渐变栏：返回 / 标题集号 / 倍速 / 更多（选集、清晰度、弹幕、重新加载）
 *  - 底部渐变区：剧集信息（可展开简介）/ 进度条 / 播放、上下集、弹幕、全屏
 *  - 全屏时隐藏系统栏，控件自动淡出
 *  - 弹幕层由 MainActivity 挂进 [videoHost]，覆盖视频区
 *
 * 播放内核完全复用 MainActivity 的 EpisodePlaybackSession / playbackCache，
 * 进度与状态由 MainActivity 的 tick 通过 [refresh] 驱动。
 */
class PhonePlayer(
    private val activity: MainActivity,
) {
    private fun dp(v: Int) = PhoneUi.dp(activity, v)
    private fun text(v: String, size: Float, color: Int = Color.WHITE) = PhoneUi.text(activity, v, size, color)

    /** 整页根容器（不含系统栏）。 */
    private var host: FrameLayout? = null
    /** 视频区：PlayerView 与弹幕层放在这里，尺寸随缩放模式变化。 */
    private var videoHost: FrameLayout? = null

    /** 顶部 + 底部控件组，统一显隐。 */
    private var controls: FrameLayout? = null
    /** 竖屏视频时浮在画面底部的一行信息。 */
    private var videoInfo: View? = null

    private var titleText: TextView? = null
    private var statusText: TextView? = null
    private var progressText: TextView? = null
    private var playButton: ImageView? = null
    private var seekBar: PlaybackSeekBar? = null
    private var episodeText: TextView? = null
    private var episodePickerText: TextView? = null
    private var introText: TextView? = null
    private var introToggle: TextView? = null
    private var danmakuButton: TextView? = null
    private var speedLabel: TextView? = null
    private var fullscreenButton: ImageView? = null
    private var centerPlay: ImageView? = null
    private var buffering: ProgressBar? = null
    private var errorBlock: View? = null
    private var errorText: TextView? = null
    private var videoInfoLine: TextView? = null

    private var fullscreen = false
    private var controlsHidByUser = false
    private var introExpanded = false
    private var lastInteraction = 0L
    private var currentDescription = ""

    // ---------------- 对外入口 ----------------

    /** 构建播放页外壳；PlayerView 与弹幕层随后由 MainActivity 放进 [videoHost]。 */
    fun render(root: FrameLayout, data: Detail) {
        clearRefs()
        // 播放页恒黑底：状态栏图标固定白色（暖玉白主题下也不切深色图标）
        activity.phoneStatusBarLight(false)
        root.setBackgroundColor(Color.BLACK)
        val page = FrameLayout(activity)
        host = page

        videoHost = FrameLayout(activity)
        page.addView(videoHost, FrameLayout.LayoutParams(-1, -1))

        // 暂停时居中显示的大播放按钮
        centerPlay = ImageView(activity).apply {
            setImageResource(R.drawable.ic_play_circle)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.argb(96, 0, 0, 0)); shape = GradientDrawable.OVAL
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { activity.togglePlaybackPhone(); touch() }
            visibility = View.GONE
        }
        page.addView(centerPlay, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))

        buffering = ProgressBar(activity).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
        }
        page.addView(buffering, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))

        val error = PhoneUi.column(activity).apply {
            background = TvStyle.shape(activity, Color.argb(210, 0, 0, 0), radius = 14)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            visibility = View.GONE
        }
        errorText = text("", 14f).apply { gravity = Gravity.CENTER }
        error.addView(errorText, LinearLayout.LayoutParams(-1, -2))
        error.addView(text("重新加载", 14f, TvStyle.accent).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { activity.retryPlaybackPhone(); touch() }
        }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
        errorBlock = error
        page.addView(error, FrameLayout.LayoutParams(dp(240), -2, Gravity.CENTER))

        buildControls(page, data)
        // 手势只挂在视频区：控件层（按钮/进度条）保持正常点击，不能被整页监听吞掉。
        videoHost?.setOnTouchListener(touchListener)
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        controlsHidByUser = false
        showControls(true)
        updateEpisode(data)
    }

    /** 把 MainActivity 创建的 PlayerView 放进视频区。 */
    fun attachPlayerView(view: View) {
        videoHost?.removeAllViews()
        videoHost?.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    /** 弹幕层挂在视频区最上层（控件之下）。 */
    fun attachDanmaku(view: View) {
        videoHost?.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    /** 切集/详情刷新时更新标题与集号信息。 */
    fun updateEpisode(data: Detail) {
        currentDescription = data.series.description
        titleText?.text = data.series.title
        episodeText?.text = "第 ${activity.phoneEpisodeIndex + 1} 集"
        episodePickerText?.text = "第 ${activity.phoneEpisodeIndex + 1} 集  ·  共${data.episodes.size}集  ›"
        introText?.text = currentDescription.ifBlank { "第 ${activity.phoneEpisodeIndex + 1} 集 · 共${data.episodes.size}集" }
        videoInfoLine?.text = currentDescription.ifBlank { "第 ${activity.phoneEpisodeIndex + 1} 集" }
    }

    /** 由 MainActivity 的 tick 每秒调用，刷新进度与状态。 */
    fun refresh(
        playing: Boolean,
        position: Long,
        duration: Long,
        bufferingNow: Boolean,
        error: String?,
        danmakuOn: Boolean,
        speed: String,
        quality: String,
    ) {
        val hasDuration = duration > 0
        if (seekBar?.scrubbing != true) {
            seekBar?.progress = if (hasDuration) (position * 1000 / duration).toInt().coerceIn(0, 1000) else 0
        }
        progressText?.text = "${formatTime(position)} / ${formatTime(duration)}"
        playButton?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        centerPlay?.visibility = if (!playing && !bufferingNow && error == null) View.VISIBLE else View.GONE
        buffering?.visibility = if (bufferingNow && error == null) View.VISIBLE else View.GONE
        errorBlock?.visibility = if (error != null) View.VISIBLE else View.GONE
        if (error != null) errorText?.text = error
        speedLabel?.text = speed
        statusText?.text = listOfNotNull(quality.ifBlank { null }, "弹幕 " + (if (danmakuOn) "开" else "关"))
            .joinToString("  ·  ")
        danmakuButton?.alpha = if (danmakuOn) 1f else .45f
        // 播放中且用户没在操作时自动收起控件（全屏与竖屏一致，参考葫芦娃红果播放页）；
        // 暂停时保持常显，避免用户找不到播放按钮。
        val idle = android.os.SystemClock.elapsedRealtime() - lastInteraction > AUTO_HIDE_MS
        if (playing && idle && !controlsHidByUser && controls?.visibility == View.VISIBLE) {
            showControls(false)
        }
        if (!playing) showControls(true)
    }

    fun isFullscreen() = fullscreen

    /** 旋转重建前记下全屏状态；新外壳渲染后恢复，避免方向弹回竖屏。 */
    fun takeFullscreenState(): Boolean {
        val was = fullscreen
        fullscreen = false
        return was
    }

    /** 新外壳渲染后恢复全屏（不重复请求方向/系统栏，调用方已经设置）。 */
    fun restoreFullscreenState(wasFullscreen: Boolean) {
        fullscreen = wasFullscreen
        fullscreenButton?.setImageResource(if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
    }

    /** 退出播放页：解除全屏并释放引用。 */
    fun release() {
        if (fullscreen) activity.exitFullscreenPhone()
        fullscreen = false
        host = null; videoHost = null; controls = null
        clearRefs()
    }

    // ---------------- 控件构建 ----------------

    private fun buildControls(page: FrameLayout, data: Detail) {
        val layer = FrameLayout(activity)
        controls = layer
        page.addView(layer, FrameLayout.LayoutParams(-1, -1))

        // 顶部栏
        val topBaseTop = dp(8)
        val top = PhoneUi.row(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(170, 0, 0, 0), Color.TRANSPARENT))
            setPadding(dp(4), topBaseTop, dp(6), dp(24))
        }
        top.addView(iconButton(R.drawable.ic_back) { activity.returnToDetailPhone() }, rowLp(dp(40), dp(40)))
        val titles = PhoneUi.column(activity).apply { setPadding(dp(2), 0, dp(8), 0) }
        titleText = text("", 16f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        titles.addView(titleText, rowLp(0, -2))
        episodeText = text("", 12f, Color.argb(190, 255, 255, 255)).apply { maxLines = 1 }
        titles.addView(episodeText, rowLp(0, -2))
        top.addView(titles, rowLp(0, -2).apply { weight = 1f })

        speedLabel = text("1×", 14f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { touch(); activity.showPhoneSpeedPicker() }
        }
        top.addView(speedLabel, rowLp(-2, -2))
        val speedAnchor = speedLabel!!
        top.addView(iconButton(R.drawable.ic_more) { touch(); activity.showPhoneMoreMenu(speedAnchor) }, rowLp(dp(40), dp(40)))
        layer.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // 底部区
        val bottomBaseBottom = dp(10)
        val bottom = PhoneUi.column(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(215, 0, 0, 0), Color.TRANSPARENT))
            setPadding(0, dp(36), 0, bottomBaseBottom)
        }
        val info = PhoneUi.column(activity).apply { setPadding(dp(16), 0, dp(16), 0) }
        episodePickerText = text("", 15f).apply {
            setPadding(0, dp(4), 0, dp(6))
            setOnClickListener { touch(); activity.showPhoneEpisodePicker() }
        }
        info.addView(episodePickerText, rowLp(-1, -2))
        val introRow = PhoneUi.row(activity)
        introText = text("", 13f, Color.argb(180, 255, 255, 255)).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, 0, 0, dp(4))
        }
        introRow.addView(introText, rowLp(0, -2).apply { weight = 1f })
        introToggle = text("展开", 12f, Color.argb(200, 255, 255, 255)).apply {
            setPadding(dp(10), dp(4), dp(2), dp(4))
            setOnClickListener {
                introExpanded = !introExpanded
                introText?.maxLines = if (introExpanded) 4 else 1
                introToggle?.text = if (introExpanded) "收起" else "展开"
                touch()
            }
        }
        introRow.addView(introToggle, rowLp(-2, -2))
        info.addView(introRow, rowLp(-1, -2))
        bottom.addView(info, rowLp(-1, -2))

        // 进度条 + 时间
        val seekRow = PhoneUi.row(activity).apply { setPadding(dp(14), dp(2), dp(16), 0) }
        val bar = PlaybackSeekBar(activity).apply { max = 1000 }
        bar.onScrubPreview = preview@{ ratio ->
            touch()
            val duration = activity.playerDuration
            if (duration <= 0) return@preview
            progressText?.text = "${formatTime((duration * ratio).toLong())} / ${formatTime(duration)}"
        }
        bar.onScrub = scrub@{ ratio, final ->
            val duration = activity.playerDuration
            if (duration <= 0 || !activity.phonePlaybackReady) return@scrub
            if (final) activity.seekToPhone((duration * ratio).toLong())
        }
        seekBar = bar
        seekRow.addView(bar, rowLp(0, dp(26)).apply { weight = 1f })
        progressText = text("", 11f, Color.argb(200, 255, 255, 255)).apply { setPadding(dp(10), 0, 0, 0) }
        seekRow.addView(progressText, rowLp(-2, -2))
        bottom.addView(seekRow, rowLp(-1, -2))

        // 操作行
        val actions = PhoneUi.row(activity).apply { setPadding(dp(12), dp(2), dp(10), dp(2)) }
        playButton = iconButton(R.drawable.ic_pause) { touch(); activity.togglePlaybackPhone() }
        actions.addView(playButton, rowLp(dp(44), dp(44)))
        actions.addView(iconButton(R.drawable.ic_skip_prev) { touch(); activity.skipEpisodePhone(-1) }, rowLp(dp(44), dp(44)))
        actions.addView(iconButton(R.drawable.ic_skip_next) { touch(); activity.skipEpisodePhone(1) }, rowLp(dp(44), dp(44)))
        statusText = text("", 12f, Color.argb(190, 255, 255, 255)).apply {
            maxLines = 1; setPadding(dp(6), 0, dp(4), 0)
        }
        actions.addView(statusText, rowLp(0, -2).apply { weight = 1f })
        danmakuButton = text("弹幕", 13f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { touch(); activity.togglePhoneDanmaku() }
        }
        actions.addView(danmakuButton, rowLp(-2, -2).apply { rightMargin = dp(4) })
        fullscreenButton = iconButton(R.drawable.ic_fullscreen) { touch(); toggleFullscreen() }
        actions.addView(fullscreenButton, rowLp(dp(44), dp(44)))
        bottom.addView(actions, rowLp(-1, -2))
        layer.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // 竖屏视频时浮在画面底部的一行简介
        val floating = PhoneUi.column(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(150, 0, 0, 0), Color.TRANSPARENT))
            setPadding(dp(16), dp(20), dp(16), dp(10))
            visibility = View.GONE
        }
        videoInfoLine = text("", 13f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { touch(); activity.showPhoneEpisodePicker() }
        }
        floating.addView(videoInfoLine, rowLp(-1, -2))
        videoInfo = floating
        page.addView(floating, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // 竖屏播放页状态栏可见，顶部标题与底部控制要让出状态栏/手势条；全屏（横屏）时
        // 系统栏隐藏、Insets 归零只剩刘海，padding 自动回落到基础值（回调可能多次派发，
        // 必须用「基础值 + 偏移」的幂等写法）
        PhoneInsets.listen(page) { topInset, bottomInset, _ ->
            top.setPadding(dp(4), topBaseTop + topInset, dp(6), dp(24))
            bottom.setPadding(0, dp(36), 0, bottomBaseBottom + bottomInset)
            floating.setPadding(dp(16), dp(20), dp(16), dp(10) + bottomInset)
        }
    }

    // ---------------- 触摸与控件显隐 ----------------

    @SuppressLint("ClickableViewAccessibility")
    private val touchListener = View.OnTouchListener { _, event ->
        if (event.actionMasked == MotionEvent.ACTION_DOWN) strokeTotal = 0f
        gesture.onTouchEvent(event)
        true
    }

    /** 本次手势累计纵向位移。 */
    private var strokeTotal = 0f
    /** 同一次手势内只切一集。 */
    private var strokeLocked = false

    private val gesture: GestureDetector by lazy {
        GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                strokeTotal = 0f
                strokeLocked = false
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (strokeLocked) return true
                strokeTotal += dy
                // 与 TV 版手势一致：dy 是「上次−当前」，上滑为正 → 下一集
                if (Math.abs(strokeTotal) > activity.resources.displayMetrics.heightPixels * 0.18f) {
                    strokeLocked = true
                    touch()
                    if (strokeTotal > 0) activity.skipEpisodePhone(1) else activity.skipEpisodePhone(-1)
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (strokeLocked) return false
                // 快速轻扫位移可能不够 18%，用速度兜底；要求纵向明显占优，避免误伤横向拖动
                if (Math.abs(vy) > Math.abs(vx) * 1.4f && Math.abs(vy) > 1600f) {
                    strokeLocked = true
                    touch()
                    if (vy < 0) activity.skipEpisodePhone(1) else activity.skipEpisodePhone(-1)
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 先判当前状态再 touch()：touch() 自己会把控件叫出来，
                // 放在后面判的话永远是「已显示」，单击就永远在切播放状态。
                val showing = controls?.visibility == View.VISIBLE
                touch()
                // 控件收起时第一下只管把控件叫回来；已经显示才执行播放/暂停，
                // 否则用户想点进度条，第一下就把视频停了。
                if (showing) activity.togglePlaybackPhone()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                touch()
                activity.togglePlaybackPhone()
                return true
            }
        })
    }

    private fun touch() {
        lastInteraction = android.os.SystemClock.elapsedRealtime()
        if (controls?.visibility != View.VISIBLE) showControls(true)
    }

    private fun showControls(visible: Boolean) {
        controlsHidByUser = !visible
        controls?.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) lastInteraction = android.os.SystemClock.elapsedRealtime()
        // 竖屏画幅的浮动信息跟随控件一起显隐；全屏时不显示
        val floating = videoInfo ?: return
        floating.visibility = if (visible && !fullscreen && portraitVideo) View.VISIBLE else View.GONE
    }

    /** 当前视频是否为竖屏画幅（由 MainActivity 在 VideoSize 回调里告知）。 */
    private var portraitVideo = false

    fun setPortraitVideo(portrait: Boolean) {
        portraitVideo = portrait
        videoInfo?.visibility = if (portrait && !fullscreen && controls?.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        fullscreenButton?.setImageResource(
            if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        if (fullscreen) activity.enterFullscreenPhone() else activity.exitFullscreenPhone()
        showControls(true)
    }

    // ---------------- 小工具 ----------------

    private fun iconButton(icon: Int, action: () -> Unit): ImageView = ImageView(activity).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener { action() }
    }

    private fun rowLp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun formatTime(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun clearRefs() {
        host = null; videoHost = null; controls = null; videoInfo = null
        titleText = null; statusText = null; progressText = null; playButton = null; seekBar = null
        episodeText = null; episodePickerText = null
        introText = null; introToggle = null; danmakuButton = null; speedLabel = null
        fullscreenButton = null; centerPlay = null; buffering = null
        errorBlock = null; errorText = null; videoInfoLine = null
    }

    private companion object {
        /** 播放中控件自动淡出的静默时长。 */
        const val AUTO_HIDE_MS = 3500L
    }
}
