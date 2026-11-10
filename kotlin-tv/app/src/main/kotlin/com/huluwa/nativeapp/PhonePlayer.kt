// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
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
 * 手机竖屏播放页，逐项对齐葫芦娃 App 的红果播放页（huluwa/ui/HongguoPlayer.kt）：
 *
 *  - 顶部状态栏：返回 / 标题 / 倍速 / 更多，压在视频上方的黑色渐变里，状态栏图标固定白色；
 *    竖屏标题只显示「第 N 集」（葫芦娃同款），简介放在底部
 *  - 点击交互：轻点画面 = 显示 / 隐藏顶部栏与底部面板（沉浸式观看）；
 *    双击画面 = 播放 / 暂停（暂停时正中浮大播放键）；上下滑动切上一集 / 下一集
 *  - 弹幕层从标题栏下沿开始滚，不与顶部按钮重叠（见 [attachDanmaku]）
 *  - 底部：剧名 + 「第N集 | 简介」（可展开）/ 白色细线进度条 /
 *    「选集 · 全N集」整行按钮 + 收藏爱心 + 全屏，与葫芦娃红果播放页一一对应
 *
 * 方向规则（严格按用户设置的「启动方向」）：
 *  - 强制竖屏：全屏也不旋转设备，只隐藏系统栏，控件仍是竖屏这一套；
 *  - 强制横屏 / 跟随系统且横过来：整个 App 走 TV 横屏模式，本类不参与渲染
 *    （见 [PhoneUi.shouldUse]），播放页就是电视播放器那套。
 *
 * 播放内核完全复用 MainActivity 的 EpisodePlaybackSession / playbackCache，
 * 进度与状态由 MainActivity 的 tick 通过 [refresh] 驱动。
 */
class PhonePlayer(
    private val activity: MainActivity,
) {
    private fun dp(v: Int) = PhoneUi.dp(activity, v)
    private fun text(v: String, size: Float, color: Int = Color.WHITE) = PhoneUi.text(activity, v, size, color)
    private fun row() = PhoneUi.row(activity)
    private fun column() = PhoneUi.column(activity)

    /** 整页根容器。 */
    private var host: FrameLayout? = null
    /** 视频区：PlayerView 与弹幕层放在这里。 */
    private var videoHost: FrameLayout? = null
    /** 顶部栏 + 底部面板。轻点画面切换显隐，隐藏后即沉浸式观看。 */
    private var controls: FrameLayout? = null
    /** 控件层当前是否隐藏（沉浸模式）。 */
    private var uiHidden = false

    private var topBar: LinearLayout? = null
    private var bottom: LinearLayout? = null
    private var titleText: TextView? = null
    private var speedLabel: TextView? = null
    private var seriesTitleText: TextView? = null
    private var introText: TextView? = null
    private var introToggle: TextView? = null
    private var episodePickerText: TextView? = null
    private var seekBar: PlaybackSeekBar? = null
    private var favoriteButton: ImageView? = null
    private var fullscreenButton: ImageView? = null
    private var centerPlay: ImageView? = null
    private var buffering: ProgressBar? = null
    private var errorBlock: View? = null
    private var errorText: TextView? = null

    private var fullscreen = false
    private var introExpanded = false
    private var currentDescription = ""
    private var currentTitle = ""
    private var episodeCount = 0
    private var favorite = false

    /** 状态栏真实高度（px）：顶部栏让位与弹幕层下移用。 */
    private var topInsetPx = 0

    // ---------------- 对外入口 ----------------

    /** 构建播放页外壳；PlayerView 与弹幕层随后由 MainActivity 放进 [videoHost]。 */
    fun render(root: FrameLayout, data: Detail) {
        clearRefs()
        currentTitle = data.series.title
        currentDescription = data.series.description
        episodeCount = data.episodes.size
        favorite = activity.phoneLibrary.favorite(data.series.id)
        // 新外壳一律先按「控件可见」起，避免沿用上一次被隐藏的状态
        uiHidden = false
        // 播放页恒黑底：状态栏图标固定白色（暖玉白主题下也不切深色图标）
        activity.phoneStatusBarLight(false)
        root.setBackgroundColor(Color.BLACK)
        val page = FrameLayout(activity)
        host = page

        videoHost = FrameLayout(activity)
        page.addView(videoHost, FrameLayout.LayoutParams(-1, -1))

        // 暂停时居中显示的大播放按钮（葫芦娃红果播放页同款）
        centerPlay = ImageView(activity).apply {
            setImageResource(R.drawable.ic_play_circle)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.argb(89, 0, 0, 0)); shape = GradientDrawable.OVAL
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { activity.togglePlaybackPhone() }
            visibility = View.GONE
        }
        page.addView(centerPlay, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))

        buffering = ProgressBar(activity).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
        }
        page.addView(buffering, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))

        val error = column().apply {
            background = TvStyle.shape(activity, Color.argb(210, 0, 0, 0), radius = 14)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            visibility = View.GONE
        }
        errorText = text("", 14f).apply { gravity = Gravity.CENTER }
        error.addView(errorText, LinearLayout.LayoutParams(-1, -2))
        error.addView(text("重新播放", 14f, TvStyle.accent).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { activity.retryPlaybackPhone() }
        }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
        errorBlock = error
        page.addView(error, FrameLayout.LayoutParams(dp(240), -2, Gravity.CENTER))

        buildControls(page)

        // 手势只挂在视频区：控件层（按钮/进度条）保持正常点击，不能被整页监听吞掉。
        videoHost?.setOnTouchListener(touchListener)
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        // 系统栏让位：顶部栏避开状态栏，底部面板避开手势条，弹幕层跟着顶部栏一起下移。
        // 回调会多次派发（旋转 / 栏显隐），统一用「基础值 + inset」的幂等写法。
        PhoneInsets.listen(page) { topInset, bottomInset, _ ->
            topInsetPx = topInset
            topBar?.setPadding(dp(4), dp(8) + topInset, dp(6), dp(24))
            bottom?.setPadding(0, dp(34), 0, dp(8) + bottomInset)
            videoHost?.findViewWithTag<View>(TAG_DANMAKU)?.let { view ->
                (view.layoutParams as? FrameLayout.LayoutParams)?.topMargin = dp(DANMAKU_TOP_DP) + topInset
                view.requestLayout()
            }
        }

        updateEpisode(data)
        applyFullscreenUi()
    }

    /** 把 MainActivity 创建的 PlayerView 放进视频区最底层。 */
    fun attachPlayerView(view: View) {
        videoHost?.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
    }

    /**
     * 弹幕层挂在视频区，但上沿让出顶部标题栏：葫芦娃红果播放页的弹幕从标题栏下方
     * 才开始滚，否则第一行弹幕会被返回键和倍速按钮压住。
     */
    fun attachDanmaku(view: View) {
        view.tag = TAG_DANMAKU
        videoHost?.addView(view, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(DANMAKU_TOP_DP) + topInsetPx })
    }

    /** 屏幕最底部离线状态条（含条上方的原因提示）：压在视频区底部，不挡控件。 */
    fun attachCacheStrip(view: View) {
        videoHost?.addView(view, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    /** 切集 / 详情刷新时更新标题、集号与简介。 */
    fun updateEpisode(data: Detail) {
        currentTitle = data.series.title
        currentDescription = data.series.description
        episodeCount = data.episodes.size
        val number = activity.phoneEpisodeIndex + 1
        titleText?.text = "第 $number 集"
        seriesTitleText?.text = currentTitle
        episodePickerText?.text = "选集 · 全${episodeCount}集"
        // 文案严格对齐葫芦娃 HongguoPlayerInfo：「第N集 · 共M集」/「第N集 | 简介」，集号不带空格
        introText?.text = if (currentDescription.isBlank()) "第${number}集 · 共${episodeCount}集"
            else "第${number}集 | $currentDescription"
        introText?.maxLines = if (introExpanded) 4 else 1
        // 简介为空时葫芦娃不显示「展开」，这里跟着切
        introToggle?.visibility = if (currentDescription.isBlank()) View.GONE else View.VISIBLE
    }

    /** 收藏态变化后刷新爱心图标。 */
    fun setFavorite(value: Boolean) {
        favorite = value
        favoriteButton?.setImageResource(if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        favoriteButton?.setColorFilter(if (favorite) 0xFFFF4D67.toInt() else Color.WHITE)
    }

    /** 由 MainActivity 的 tick 每秒调用，刷新进度与状态。 */
    fun refresh(
        playing: Boolean,
        position: Long,
        duration: Long,
        bufferingNow: Boolean,
        error: String?,
        speed: String,
    ) {
        if (seekBar?.scrubbing != true) {
            seekBar?.progress = if (duration > 0) (position * 1000 / duration).toInt().coerceIn(0, 1000) else 0
        }
        centerPlay?.visibility = if (!playing && !bufferingNow && error == null) View.VISIBLE else View.GONE
        buffering?.visibility = if (bufferingNow && error == null) View.VISIBLE else View.GONE
        errorBlock?.visibility = if (error != null) View.VISIBLE else View.GONE
        if (error != null) errorText?.text = error
        speedLabel?.text = speed
    }

    fun isFullscreen() = fullscreen

    /** 旋转重建前记下全屏状态；新外壳渲染后恢复，避免方向弹回竖屏。 */
    fun takeFullscreenState(): Boolean {
        val was = fullscreen
        fullscreen = false
        return was
    }

    /** 新外壳渲染后恢复全屏（不重复请求方向 / 系统栏，调用方已经设置）。 */
    fun restoreFullscreenState(wasFullscreen: Boolean) {
        fullscreen = wasFullscreen
        applyFullscreenUi()
    }

    /** 退出播放页：解除全屏并释放引用。 */
    fun release() {
        if (fullscreen) activity.exitFullscreenPhone()
        fullscreen = false
        host = null; videoHost = null; controls = null
        clearRefs()
    }

    // ---------------- 控件构建 ----------------

    private fun buildControls(page: FrameLayout) {
        val layer = FrameLayout(activity)
        controls = layer
        page.addView(layer, FrameLayout.LayoutParams(-1, -1))

        // ---- 顶部状态栏：返回 / 标题 / 倍速 / 更多（葫芦娃红果播放页同构） ----
        val top = row().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(178, 0, 0, 0), Color.TRANSPARENT))
            setPadding(dp(4), dp(8), dp(6), dp(24))
        }
        topBar = top
        top.addView(iconButton(R.drawable.ic_back, 26, 44) { activity.returnToDetailPhone() }, rowLp(dp(44), dp(48)))
        titleText = text("", 18f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        }
        top.addView(titleText, rowLp(0, -2).apply { weight = 1f; leftMargin = dp(2) })

        // 倍速：时钟图标 + 「1×」文字，点击弹出本剧倍速选择器（与葫芦娃一致）
        val speed = row().apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(10), dp(6))
            setOnClickListener { activity.showPhoneSpeedPicker() }
        }
        speed.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_speed); setColorFilter(Color.WHITE)
        }, rowLp(dp(20), dp(20)).apply { rightMargin = dp(6) })
        speedLabel = text("1×", 15f).apply { maxLines = 1 }
        speed.addView(speedLabel, rowLp(-2, -2))
        top.addView(speed, rowLp(-2, dp(44)))
        val speedAnchor = speed
        top.addView(iconButton(R.drawable.ic_more, 22, 44) { activity.showPhoneMoreMenu(speedAnchor) }, rowLp(dp(44), dp(44)))
        layer.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // ---- 底部：剧集信息 + 进度条 + 选集行（葫芦娃 HongguoPlayerInfo + 选集按钮） ----
        val bottomBar = column().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(225, 0, 0, 0), Color.TRANSPARENT))
            setPadding(0, dp(34), 0, dp(8))
        }
        bottom = bottomBar

        val info = column().apply { setPadding(dp(16), 0, dp(16), 0) }
        val titleRow = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(2))
            setOnClickListener { activity.showPhoneEpisodePicker() }
        }
        seriesTitleText = text("", 18f).apply {
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            // 葫芦娃 HongguoPlayerInfo：lineHeight 26sp → 行距 = 26 - 18 = 8sp 额外间距
            setLineSpacing(dp(8).toFloat(), 1f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        titleRow.addView(seriesTitleText, rowLp(0, -2))
        titleRow.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_chevron_right); setColorFilter(Color.WHITE)
        }, rowLp(dp(20), dp(20)).apply { leftMargin = dp(2) })
        // heightIn(min = 32dp)，内容垂直居中
        info.addView(titleRow, rowLp(-1, dp(32)))

        val introRow = row()
        introText = text("", 14f, Color.argb(184, 255, 255, 255)).apply {
            // 葫芦娃 HongguoPlayerInfo：lineHeight 22sp → 16sp
            setLineSpacing(dp(8).toFloat(), 1f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            // padding(vertical = 4dp)
            setPadding(0, dp(4), 0, dp(4))
        }
        introRow.addView(introText, rowLp(0, -2).apply { weight = 1f })
        // 葫芦娃：只有简介非空才出现「展开 / 收起」按钮
        introToggle = text("展开", 14f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                introExpanded = !introExpanded
                introText?.maxLines = if (introExpanded) 4 else 1
                introToggle?.text = if (introExpanded) "收起" else "展开"
            }
        }
        introToggle?.visibility = if (currentDescription.isBlank()) View.GONE else View.VISIBLE
        introRow.addView(introToggle, rowLp(-2, dp(32)))
        info.addView(introRow, rowLp(-1, -2))
        bottomBar.addView(info, rowLp(-1, -2))

        // 进度条：白色细线 + 小圆点（葫芦娃 HongguoSeekBar 外观）
        val seek = row().apply { setPadding(dp(16), dp(4), dp(16), dp(2)) }
        val bar = PlaybackSeekBar(
            activity,
            trackColor = Color.argb(51, 255, 255, 255),
            fillColor = Color.argb(166, 255, 255, 255),
            thumbRadiusDp = 2.5f,
            trackHeightDp = 1.5f,
        ).apply { max = 1000 }
        bar.onScrub = scrub@{ ratio, final ->
            val duration = activity.playerDuration
            if (duration <= 0 || !activity.phonePlaybackReady) return@scrub
            if (final) activity.seekToPhone((duration * ratio).toLong())
        }
        seekBar = bar
        seek.addView(bar, rowLp(0, dp(24)).apply { weight = 1f })
        bottomBar.addView(seek, rowLp(-1, -2))

        // 「选集 · 全N集」整行按钮 + 收藏 + 全屏（葫芦娃同构）
        val actions = row().apply { setPadding(dp(16), dp(2), dp(16), dp(6)) }
        val picker = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = TvStyle.shape(activity, Color.rgb(0x15, 0x15, 0x15), radius = 10)
            setPadding(dp(16), 0, dp(12), 0)
            setOnClickListener { activity.showPhoneEpisodePicker() }
        }
        episodePickerText = text("选集 · 全${episodeCount}集", 15f).apply { maxLines = 1 }
        picker.addView(episodePickerText, rowLp(0, -2).apply { weight = 1f })
        picker.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_keyboard_arrow_up); setColorFilter(Color.WHITE)
        }, rowLp(dp(22), dp(22)))
        actions.addView(picker, rowLp(0, dp(44)).apply { weight = 1f })

        favoriteButton = ImageView(activity).apply {
            setImageResource(if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            setColorFilter(if (favorite) 0xFFFF4D67.toInt() else Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { toggleFavorite() }
        }
        actions.addView(favoriteButton, rowLp(dp(40), dp(40)).apply { leftMargin = dp(8) })

        fullscreenButton = ImageView(activity).apply {
            setImageResource(R.drawable.ic_fullscreen)
            setColorFilter(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { toggleFullscreen() }
        }
        actions.addView(fullscreenButton, rowLp(dp(40), dp(40)).apply { leftMargin = dp(4) })
        bottomBar.addView(actions, rowLp(-1, -2))

        layer.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    /** 全屏图标与文案随状态切换；强制竖屏下全屏只是隐藏系统栏，控件布局不变。 */
    private fun applyFullscreenUi() {
        fullscreenButton?.setImageResource(if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
    }

    // ---------------- 触摸 ----------------

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
                    if (strokeTotal > 0) activity.skipEpisodePhone(1) else activity.skipEpisodePhone(-1)
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (strokeLocked) return false
                // 快速轻扫位移可能不够 18%，用速度兜底；要求纵向明显占优，避免误伤横向拖动
                if (Math.abs(vy) > Math.abs(vx) * 1.4f && Math.abs(vy) > 1600f) {
                    strokeLocked = true
                    if (vy < 0) activity.skipEpisodePhone(1) else activity.skipEpisodePhone(-1)
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 轻点画面 = 切换控件显隐，进入 / 退出沉浸式观看
                toggleUiVisibility()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 双击画面 = 播放 / 暂停（暂停时正中还有个大播放键）
                activity.togglePlaybackPhone()
                return true
            }
        })
    }

    // ---------------- 操作 ----------------

    /**
     * 轻点画面切换控件显隐：隐藏时顶部栏与底部面板淡出，只留画面与暂停大播放键，
     * 即沉浸式观看；再点一次恢复。只动控件层，不碰播放内核。
     */
    private fun toggleUiVisibility() {
        val layer = controls ?: return
        uiHidden = !uiHidden
        layer.animate().cancel()
        layer.visibility = View.VISIBLE
        layer.animate()
            .alpha(if (uiHidden) 0f else 1f)
            .setDuration(180)
            .withEndAction {
                // 动画期间用户可能又点了一次，状态已变回显示时不能把它藏掉
                if (uiHidden) layer.visibility = View.GONE
            }
            .start()
    }

    /** 收藏按钮：与详情页共用 library.toggle，图标即时切换。 */
    private fun toggleFavorite() {
        val series = activity.phoneCurrentSeries ?: return
        activity.phoneFavoriteMonitor.stop()
        val saved = activity.phoneLibrary.toggle(series)
        if (saved) activity.phoneLibrary.observeFavorite(series.id, episodeCount, System.currentTimeMillis(), acknowledge = true)
        setFavorite(saved)
        PhoneUi.toast(activity, if (saved) "已收藏" else "已取消收藏")
    }

    /**
     * 全屏：强制竖屏下只隐藏系统栏（不转屏）；跟随系统下才真正转横屏，
     * 转过去之后由 onConfigurationChanged 换成电视播放器那套界面。
     */
    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        if (fullscreen) activity.enterFullscreenPhone() else activity.exitFullscreenPhone()
        applyFullscreenUi()
        activity.applyPhoneFullscreen(fullscreen)
    }

    // ---------------- 小工具 ----------------

    /** 图标按钮：图片按 [sizeDp] 显示，外面留足 [touchDp] 的点击区。 */
    private fun iconButton(icon: Int, sizeDp: Int, touchDp: Int, action: () -> Unit): ImageView =
        ImageView(activity).apply {
            setImageResource(icon)
            setColorFilter(Color.WHITE)
            val pad = ((touchDp - sizeDp) / 2).coerceAtLeast(0)
            setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
            setOnClickListener { action() }
        }

    private fun rowLp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun clearRefs() {
        host = null; videoHost = null; controls = null
        topBar = null; bottom = null; titleText = null; speedLabel = null
        seriesTitleText = null; introText = null; introToggle = null; episodePickerText = null
        seekBar = null; favoriteButton = null; fullscreenButton = null
        centerPlay = null; buffering = null; errorBlock = null; errorText = null
    }

    private companion object {
        /** 弹幕层上沿：标题栏高度（dp），与顶部栏的视觉高度对齐。 */
        const val DANMAKU_TOP_DP = 52
        /** 弹幕层的 view tag：Insets 变化后按它找回来重设上边距。 */
        const val TAG_DANMAKU = "phone-danmaku-layer"
    }
}
