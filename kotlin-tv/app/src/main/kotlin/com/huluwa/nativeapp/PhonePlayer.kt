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
import androidx.media3.common.VideoSize
import com.hongguotv.core.Detail

/**
 * 手机播放页，逐项对齐葫芦娃 App 的红果播放页（huluwa/ui/HongguoPlayer.kt），
 * 同一套类同时负责「竖屏播放」与「横屏全屏」两种皮肤：
 *
 *  - 竖屏皮肤：顶部返回 / 「第 N 集」/ 倍速 / 更多，压在黑色渐变里；
 *    底部剧名 + 「第N集 | 简介」（可展开）/ 白色细线进度条 /
 *    「选集 · 全N集」整行按钮 + 收藏爱心 + 全屏
 *  - 横屏全屏皮肤（葫芦娃 fullscreen 同款）：顶部标题变成「剧名 · 第N集」；
 *    底部换成一行紧凑控制条（播放/暂停、时间、选集、退出全屏），
 *    不再显示剧名行与简介；轻点画面显隐控件
 *  - 点击交互：竖屏轻点 = 播放 / 暂停，双击 = 播放 / 暂停，上下滑动切集；
 *    横屏全屏轻点 = 显隐控件；双击也是播放 / 暂停
 *  - 弹幕层：竖屏从标题栏下沿开始滚；横屏全屏直接顶到标题栏位置，且只在沉浸观看时显示
 *    （见 [attachDanmaku] / [syncDanmakuWithControls]）
 *
 * 方向规则：
 *  - 全屏按钮：强制竖屏 / 跟随系统 → 旋转成横屏并换成横屏全屏皮肤
 *    （见 [PhoneUi.shouldUsePhoneFullscreen]）；强制横屏直接就是 TV 播放器；
 *  - 强制横屏 / 跟随系统且横着拿（非全屏）→ 整个 App 走 TV 模式，本类不参与渲染。
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
    private var playerView: View? = null
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
    /** 横屏全屏控制条：播放/暂停图标、时间文案、选集按钮。 */
    private var playPauseButton: ImageView? = null
    private var timeText: TextView? = null
    private var fsEpisodeButton: TextView? = null
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
    /** 最近一次 refresh 的播放态与时间，切换皮肤（旋转）重建控件后用来补一次显示。 */
    private var lastPlaying = false
    private var lastPosition = 0L
    private var lastDuration = 0L

    /** 状态栏真实高度（px）：顶部栏让位与弹幕层下移用。 */
    private var topInsetPx = 0

    // ---------------- 对外入口 ----------------

    /** 构建播放页外壳；PlayerView 与弹幕层随后由 MainActivity 放进 [videoHost]。 */
    fun render(root: FrameLayout, data: Detail, fullscreen: Boolean = false) {
        clearRefs()
        currentTitle = data.series.title
        currentDescription = data.series.description
        episodeCount = data.episodes.size
        favorite = activity.phoneLibrary.favorite(data.series.id)
        // 全屏皮肤由调用方按当前方向传入：横屏重建后外壳要直接长成横屏那套，
        // 不能再闪一帧竖屏布局
        this.fullscreen = fullscreen
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

        // 系统栏让位：竖屏皮肤顶部栏避开状态栏，底部面板避开手势条，弹幕层跟着顶部栏一起下移。
        // 横屏全屏皮肤把标题与控制条都让到刘海那一侧，所以顶栏只垫左/右安全区，不再垫上下。
        // 回调会多次派发（旋转 / 栏显隐），统一用「基础值 + inset」的幂等写法。
        PhoneInsets.listen(page) { topInset, bottomInset, _ ->
            topInsetPx = topInset
            if (fullscreen) {
                val side = PhoneInsets.sideIndicator
                topBar?.setPadding(dp(4) + side, dp(8), dp(6) + side, dp(16))
                bottom?.setPadding(side, dp(24), side, dp(8) + bottomInset)
            } else {
                topBar?.setPadding(dp(4), dp(8) + topInset, dp(6), dp(24))
                // 竖屏底部面板只让手势条；横屏皮肤的侧边安全区不影响竖屏排版
                bottom?.setPadding(0, dp(34), 0, dp(8) + bottomInset)
            }
            videoHost?.findViewWithTag<View>(TAG_DANMAKU)?.let { view ->
                (view.layoutParams as? FrameLayout.LayoutParams)?.topMargin = danmakuTopMargin()
                view.requestLayout()
            }
        }

        updateEpisode(data)
        applyFullscreenUi()
    }

    /** 把 MainActivity 创建的 PlayerView 放进视频区最底层。 */
    fun attachPlayerView(view: View) {
        playerView = view
        videoHost?.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
    }

    /** 竖屏播放横向视频时，将画面从居中位置上移 25dp。 */
    fun updateVideoPosition(size: VideoSize) {
        val landscape = size.height > 0 && size.width * size.pixelWidthHeightRatio > size.height
        playerView?.translationY = if (!fullscreen && landscape) -dp(25).toFloat() else 0f
    }

    /**
     * 弹幕层挂在视频区，上沿按皮肤走：
     *  - 竖屏：让出顶部标题栏（葫芦娃红果播放页同款），第一行弹幕不被返回键与倍速压住；
     *  - 横屏全屏：直接顶到顶部标题栏的位置（标题栏那一行控件占位），沉浸观看时
     *    控件整层淡出，弹幕就成为画面最上方的那一排。
     */
    fun attachDanmaku(view: View) {
        view.tag = TAG_DANMAKU
        videoHost?.addView(view, FrameLayout.LayoutParams(-1, -1).apply { topMargin = danmakuTopMargin() })
        // 横屏全屏只在沉浸（控件隐藏）时显示弹幕
        if (fullscreen) view.visibility = if (uiHidden) View.VISIBLE else View.INVISIBLE
    }

    /** 弹幕层上边距（px）：横屏贴着标题栏顶部，竖屏让出标题栏整行。 */
    private fun danmakuTopMargin(): Int = if (fullscreen) 0 else dp(DANMAKU_TOP_DP) + topInsetPx

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
        // 竖屏标题只写集号；横屏全屏按葫芦娃写成「剧名 · 第N集」
        titleText?.text = if (fullscreen) "$currentTitle · 第 $number 集" else "第 $number 集"
        seriesTitleText?.text = currentTitle
        episodePickerText?.text = "选集 · 全${episodeCount}集"
        fsEpisodeButton?.text = "选集 · 全${episodeCount}集"
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
        lastPlaying = playing; lastPosition = position; lastDuration = duration
        if (seekBar?.scrubbing != true) {
            seekBar?.progress = if (duration > 0) (position * 1000 / duration).toInt().coerceIn(0, 1000) else 0
        }
        centerPlay?.visibility = if (!playing && !bufferingNow && error == null) View.VISIBLE else View.GONE
        buffering?.visibility = if (bufferingNow && error == null) View.VISIBLE else View.GONE
        errorBlock?.visibility = if (error != null) View.VISIBLE else View.GONE
        if (error != null) errorText?.text = error
        speedLabel?.text = speed
        playPauseButton?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        if (seekBar?.scrubbing != true) timeText?.text = "${formatTime(position)} / ${formatTime(duration)}"
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

    /**
     * 退出播放页：解除全屏并释放引用（方向由 MainActivity 的调用方负责交还）。
     */
    fun release() {
        // 外壳即将被替换或页面正在离开：只恢复系统栏，不重建、也不交还方向。
        // 方向交还交给真正退出全屏的入口（exitFullscreenSkin）或 returnToDetail 处理；
        // 旋转重建同样会走 release，那里绝不能把全屏会话的方向掰回去。
        if (fullscreen) activity.exitFullscreenPhone(rebuild = false, restoreOrientation = false)
        fullscreen = false
        host = null; videoHost = null; controls = null
        clearRefs()
    }

    // ---------------- 控件构建 ----------------

    /**
     * 控件层按皮肤分两套：
     *  - 竖屏：[buildPortraitControls]，剧名 + 简介 + 进度条 + 选集行；
     *  - 横屏全屏：[buildFullscreenControls]，紧凑一行控制条，对应葫芦娃 fullscreen 布局。
     * 两套共用顶部状态栏（横屏时标题多带剧名）。
     */
    private fun buildControls(page: FrameLayout) {
        val layer = FrameLayout(activity)
        controls = layer
        page.addView(layer, FrameLayout.LayoutParams(-1, -1))
        buildTopBar(layer)
        if (fullscreen) buildFullscreenControls(layer) else buildPortraitControls(layer)
    }

    /** 顶部状态栏：返回 / 标题 / 倍速 / 更多（葫芦娃红果播放页同构，两套皮肤共用）。 */
    private fun buildTopBar(layer: FrameLayout) {
        val top = row().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(178, 0, 0, 0), Color.TRANSPARENT))
            setPadding(dp(4), dp(8), dp(6), dp(24))
        }
        topBar = top
        // 全屏横屏时返回键即「退出全屏」，与葫芦娃 leavePlayer 一致
        top.addView(iconButton(R.drawable.ic_back, 26, 44) {
            if (fullscreen) exitFullscreenSkin() else activity.returnToDetailPhone()
        }, rowLp(dp(44), dp(48)))
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
    }

    /** 竖屏皮肤底部：剧集信息 + 进度条 + 选集行（葫芦娃 HongguoPlayerInfo + 选集按钮）。 */
    private fun buildPortraitControls(layer: FrameLayout) {
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
        // 宽度 0 + weight 1：占满箭头之外的剩余宽度，超长由 ellipsize 截断
        titleRow.addView(seriesTitleText, rowLp(0, -2).apply { weight = 1f })
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
            setOnClickListener { enterFullscreenFromButton() }
        }
        actions.addView(fullscreenButton, rowLp(dp(40), dp(40)).apply { leftMargin = dp(4) })
        bottomBar.addView(actions, rowLp(-1, -2))

        layer.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    /**
     * 横屏全屏皮肤底部：进度条 + 一行紧凑控制条（葫芦娃 fullscreen 布局）。
     * 播放/暂停 · 时间 · 选集 · 退出全屏；不再重复剧名与简介。
     */
    private fun buildFullscreenControls(layer: FrameLayout) {
        val bottomBar = column().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(191, 0, 0, 0), Color.TRANSPARENT))
            setPadding(0, dp(24), 0, dp(8))
        }
        bottom = bottomBar

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

        val actions = row().apply { setPadding(dp(8), 0, dp(8), 0) }
        playPauseButton = ImageView(activity).apply {
            setImageResource(R.drawable.ic_play_arrow); setColorFilter(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { activity.togglePlaybackPhone() }
        }
        actions.addView(playPauseButton, rowLp(dp(44), dp(44)))

        timeText = text("00:00 / 00:00", 13f).apply { maxLines = 1 }
        actions.addView(timeText, rowLp(-2, -2).apply { leftMargin = dp(4) })

        // 中间弹性留白：时间靠左，选集与退出靠右
        actions.addView(View(activity), rowLp(0, 1).apply { weight = 1f })

        fsEpisodeButton = text("选集 · 全${episodeCount}集", 14f).apply {
            maxLines = 1
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            // 淡色描边圆角块，和竖屏的「选集 · 全N集」按钮保持同一视觉语言
            background = GradientDrawable().apply {
                setColor(Color.argb(38, 255, 255, 255))
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { activity.showPhoneEpisodePicker() }
        }
        actions.addView(fsEpisodeButton, rowLp(-2, dp(40)).apply { rightMargin = dp(8) })

        fullscreenButton = ImageView(activity).apply {
            setImageResource(R.drawable.ic_fullscreen_exit)
            setColorFilter(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { exitFullscreenSkin() }
        }
        actions.addView(fullscreenButton, rowLp(dp(44), dp(44)))
        bottomBar.addView(actions, rowLp(-1, -2))

        layer.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    /** 全屏图标随状态切换（只有竖屏皮肤有这个按钮）。 */
    private fun applyFullscreenUi() {
        fullscreenButton?.setImageResource(if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        playPauseButton?.setImageResource(if (lastPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        timeText?.text = "${formatTime(lastPosition)} / ${formatTime(lastDuration)}"
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
                // 轻点画面 = 切换控件显隐，进入 / 退出沉浸式观看（横竖屏皮肤一致）
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
        // 横屏全屏的弹幕跟着控件一起进出：控件在时让位，沉浸时才铺到标题栏位置
        syncDanmakuWithControls()
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

    /**
     * 横屏全屏的弹幕只在沉浸观看时显示：控件可见时整层隐藏，避免与顶栏/控制条抢位置。
     *
     * 用 INVISIBLE 而不是 GONE：弹幕层要保持原有布局尺寸（轨道按高度算），
     * 也要让绘制继续跑，恢复显示的瞬间就是连续的画面。
     * 弹幕总开关关闭时 DanmakuView 自己会置 GONE，这里不能把它改成别的状态。
     */
    private fun syncDanmakuWithControls() {
        if (!fullscreen) return
        val danmaku = videoHost?.findViewWithTag<View>(TAG_DANMAKU) ?: return
        if (danmaku.visibility == View.GONE) return
        danmaku.visibility = if (uiHidden) View.VISIBLE else View.INVISIBLE
    }

    /** 弹幕层重新 bind 后会把自己置为 VISIBLE，宿主需要再按沉浸状态压一次。 */
    fun refreshDanmakuVisibility() = syncDanmakuWithControls()

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
     * 全屏：锁成横屏并换成横屏全屏皮肤（见 [PhoneUi.shouldUsePhoneFullscreen]）。
     * 旋转由 Activity 自己做（manifest configChanges=orientation），新外壳在
     * onConfigurationChanged 里按全屏标记重建，所以这里只改方向、不重建 UI。
     */
    fun enterFullscreenFromButton() {
        if (fullscreen || activity.currentPhonePlayer !== this) return
        fullscreen = true
        activity.enterFullscreenPhone()
        applyFullscreenUi()
        activity.applyPhoneFullscreen(true)
        // 设备本来就是横的（比如刚横过来）时不会触发方向回调：
        // 外壳仍是竖屏那套，这里补一次按全屏标记重挂，避免「点了没反应」
        if (!activity.phonePlayerIsFullscreenSkin()) activity.rebuildPhonePlayerForLayout()
    }

    /**
     * 退出全屏：转回竖屏并回到竖屏皮肤（葫芦娃 leavePlayer → fullscreen = false）。
     *
     * `exitFullscreenPhone()` 可能直接重挂外壳（设备本来就竖着；或已横屏时锁回竖屏后
     * 主动重建），那一刻本实例的控件已被 clearRefs 清空，所以刷新动作都放在它之前；
     * 每次进这里也先确认自己仍是当前实例，避免对已被替换的外壳做操作。
     */
    fun exitFullscreenSkin() {
        if (!fullscreen || activity.currentPhonePlayer !== this) return
        fullscreen = false
        applyFullscreenUi()
        activity.exitFullscreenPhone()
    }

    /** 返回键在全屏播放时退出全屏（葫芦娃 BackHandler 同款），由 MainActivity 的 goBack 调用。 */
    fun exitFullscreenFromBack() = exitFullscreenSkin()

    // ---------------- 小工具 ----------------

    /** 毫秒 → mm:ss（与电视版 HUD 的时间文案一致）。 */
    private fun formatTime(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

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
        host = null; videoHost = null; playerView = null; controls = null
        topBar = null; bottom = null; titleText = null; speedLabel = null
        seriesTitleText = null; introText = null; introToggle = null; episodePickerText = null
        playPauseButton = null; timeText = null; fsEpisodeButton = null
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
