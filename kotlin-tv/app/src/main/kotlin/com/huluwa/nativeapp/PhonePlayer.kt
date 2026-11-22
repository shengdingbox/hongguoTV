// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import androidx.core.graphics.drawable.DrawableCompat
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
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
 * 手机播放页，逐项对齐第三方参考实现的播放页（同类 App 的 HongguoPlayer），
 * 同一套类同时负责「竖屏播放」与「横屏全屏」两种皮肤：
 *
 *  - 竖屏皮肤：顶部返回 / 「第 N 集」/ 倍速 / 更多，压在黑色渐变里；
 *    底部剧名 + 「第N集 | 简介」（可展开）/ 白色细线进度条 /
 *    「选集 · 全N集」整行按钮 + 收藏爱心；横向视频下方居中显示「全屏观看」
 *  - 横屏全屏皮肤（第三方参考实现 fullscreen 同款）：顶部标题变成「剧名 · 第N集」；
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
 * 状态由播放器回调即时同步，进度由 MainActivity 的 tick 通过 [refresh] 定时刷新。
 */
class PhonePlayer(
    private val activity: MainActivity,
    private val loadCover: (ImageView, String) -> Unit,
) {
    private fun dp(v: Int) = PhoneUi.dp(activity, v)
    private fun text(v: String, size: Float, color: Int = Color.WHITE) = PhoneUi.text(activity, v, size, color)
    private fun row() = PhoneUi.row(activity)
    private fun column() = PhoneUi.column(activity)

    /** 整页根容器。 */
    private var host: FrameLayout? = null
    private var episodePager: PhoneEpisodePager? = null
    private var episodePlaceholder: View? = null
    private var displayedEpisodeId: String? = null
    private var currentCover = ""
    /** 视频区：PlayerView 与弹幕层放在这里。 */
    private var videoHost: FrameLayout? = null
    private var playerView: View? = null
    private var videoAspectRatio = 0f
    private var episodeSheetTopOnScreen: Int? = null
    private var episodeSheetProgress = 0f
    private var episodePreviewOrigin: RectF? = null
    private var previewDocked = false
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
    /** 竖屏底部信息内容区（剧名/集数 → 选集/收藏）的容器，用来精确放置长按倍速状态。 */
    private var portraitInfoContent: LinearLayout? = null
    private var fullscreenButton: ImageView? = null
    private var portraitFullscreenButton: View? = null
    private var centerPlay: ImageView? = null
    private var buffering: ProgressBar? = null
    private var errorBlock: View? = null
    private var errorText: TextView? = null
    /** 长按倍速状态：只保留底部黄色一行提示，长按期间播放页其余信息全部隐藏。 */
    private var speedBoostOverlay: View? = null
    private var speedBoostLabel: LinearLayout? = null
    /** 长按前用户是否已经是沉浸模式；松开后按原样恢复，不能把原本隐藏的控件硬显示出来。 */
    private var uiHiddenBeforeSpeedBoost = false
    /** 长按瞬间原竖屏剧集/收藏内容区的上下边界（页面坐标）。 */
    private var speedBoostAreaTop = 0
    private var speedBoostAreaBottom = 0
    private var speedBoostImmersivePending: Runnable? = null

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

    /** 长按触发与松手判定。手指按住和倍速锁定分开，刷新进度时不能误清锁定状态。 */
    private var longPressRunnable: Runnable? = null
    private var longPressFingerDown = false
    private var longPressTriggered = false
    private var longPressLocked = false
    private var longPressDownX = 0f
    private var longPressDownY = 0f
    private var longPressSpeedBefore = 1f
    private var longPressSpeedActive = false

    /** 状态栏真实高度（px）：顶部栏让位与弹幕层下移用。 */
    private var topInsetPx = 0

    // ---------------- 对外入口 ----------------

    /** 构建播放页外壳；PlayerView 与弹幕层随后由 MainActivity 放进 [videoHost]。 */
    fun render(root: FrameLayout, data: Detail, fullscreen: Boolean = false) {
        clearRefs()
        resetSpeedBoost(restore = true)
        currentTitle = data.series.title
        currentDescription = data.series.description
        currentCover = data.series.cover
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
        val episodePage = FrameLayout(activity)
        host = episodePage
        val pager = PhoneEpisodePager(activity, episodePage,
            preview = { index -> buildEpisodePlaceholder(index, showInfo = true) },
            canSwipe = { episodeSheetTopOnScreen == null && seekBar?.scrubbing != true },
            onSelect = { index -> activity.skipEpisodePhone(index - activity.phoneEpisodeIndex) },
        )
        episodePager = pager
        page.addView(pager, FrameLayout.LayoutParams(-1, -1))

        videoHost = FrameLayout(activity).apply {
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyEpisodeSheetTransform() }
        }
        episodePage.addView(videoHost, FrameLayout.LayoutParams(-1, -1))

        // 暂停时居中显示的大播放按钮（第三方参考实现同款）
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
        videoHost?.addView(centerPlay, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))

        buffering = ProgressBar(activity).apply {
            DrawableCompat.setTint(indeterminateDrawable, Color.WHITE)
            visibility = View.GONE
        }
        videoHost?.addView(buffering, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))

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
        videoHost?.addView(error, FrameLayout.LayoutParams(dp(240), -2, Gravity.CENTER))

        buildSpeedBoostOverlay(videoHost ?: episodePage)

        buildControls(if (fullscreen) page else episodePage)
        // 底部倍速状态始终压在最上层，长按沉浸时仍可见。
        speedBoostOverlay?.bringToFront()
        // 顶栏（以及横屏全屏控制条）保持固定，视频和竖屏剧集信息随整页翻动。
        buildTopBar(page)

        // 手势只挂在视频区：控件层（按钮/进度条）保持正常点击，不能被整页监听吞掉。
        videoHost?.setOnTouchListener(touchListener)
        page.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateVideoLayout() }
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
            updateVideoLayout()
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
        val frame = view.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) ?: view
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePortraitFullscreenButton() }
        updateVideoLayout()
    }

    fun updateVideoPosition(size: VideoSize) {
        videoAspectRatio = if (size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 0f
        updateVideoLayout()
    }

    /** 只有竖屏页面中的横向视频需要在选集面板上方预留播放区域。 */
    fun episodePreviewAspectRatio(): Float? = videoAspectRatio.takeIf { !fullscreen && it > 1f }

    fun setEpisodeSheetTop(topOnScreen: Int?) {
        if (topOnScreen == null || episodeSheetTopOnScreen == null) {
            episodePreviewOrigin = null
            episodeSheetProgress = 0f
        }
        episodeSheetTopOnScreen = topOnScreen
        updateVideoLayout()
    }

    fun setEpisodeSheetProgress(progress: Float) {
        episodeSheetProgress = progress.coerceIn(0f, 1f)
        applyEpisodeSheetTransform()
    }

    /** 画面在页面中的实际位置，包含留黑边后的尺寸与播放器位移。 */
    private fun videoBoundsOnPage(): RectF? {
        val page = host ?: return null
        val view = playerView ?: return null
        val frame = view.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) ?: view
        if (frame.width <= 0 || frame.height <= 0) return null
        val pageLocation = IntArray(2)
        val frameLocation = IntArray(2)
        page.getLocationOnScreen(pageLocation)
        frame.getLocationOnScreen(frameLocation)
        val left = (frameLocation[0] - pageLocation[0]).toFloat()
        val top = (frameLocation[1] - pageLocation[1]).toFloat()
        return RectF(left, top, left + frame.width, top + frame.height)
    }

    /**
     * 长按倍速状态放在原先竖屏“剧名/集数/简介/选集/收藏”内容区。
     *
     * bottomBar 顶部带 34dp 渐变留白，不能拿整块面板居中；这里按实际内容区
     * （剧名/集数行到选集/收藏行）的上下边界定位，确保状态就在用户熟悉的位置。
     */
    private fun updateSpeedBoostPosition() {
        val overlay = speedBoostOverlay ?: return
        val label = speedBoostLabel ?: return
        val layout = label.layoutParams as? FrameLayout.LayoutParams ?: return
        if (overlay.height <= 0) return
        if (speedBoostAreaTop <= 0 || speedBoostAreaBottom <= speedBoostAreaTop) {
            // 横屏全屏或尚未完成布局：贴近底部控制条上方。
            layout.bottomMargin = if (fullscreen) dp(64) else dp(132) + topInsetPx
            label.layoutParams = layout
            return
        }
        val labelHeight = label.height.takeIf { it > 0 } ?: label.measuredHeight.takeIf { it > 0 } ?: dp(42)
        val areaHeight = (speedBoostAreaBottom - speedBoostAreaTop).coerceAtLeast(labelHeight)
        val bottom = ((areaHeight - labelHeight) / 2 - dp(4)).coerceAtLeast(dp(8))
        if (layout.bottomMargin != bottom) {
            layout.bottomMargin = bottom
            label.layoutParams = layout
        }
    }

    /** 隐藏底栏前记录原竖屏信息内容区的真实上下边界，作为倍速状态的落点。 */
    private fun captureSpeedBoostArea() {
        val page = host ?: return
        val content = portraitInfoContent ?: return
        if (content.height <= 0) return
        val pageLocation = IntArray(2)
        val contentLocation = IntArray(2)
        page.getLocationOnScreen(pageLocation)
        content.getLocationOnScreen(contentLocation)
        speedBoostAreaTop = (contentLocation[1] - pageLocation[1]).coerceAtLeast(0)
        speedBoostAreaBottom = (speedBoostAreaTop + content.height).coerceAtMost(page.height)
    }

    private fun applyEpisodeSheetTransform() {
        val page = host ?: return
        val video = videoHost ?: return
        val params = video.layoutParams as FrameLayout.LayoutParams
        val targetWidth = if (params.width > 0) params.width else page.width
        val targetHeight = if (params.height > 0) params.height else page.height
        // 新视口完成布局后再应用位移，避免首帧仍按旧的整屏尺寸绘制造成跳动。
        if (video.width != targetWidth || video.height != targetHeight || video.top != params.topMargin) return
        playerView?.translationY = if (!previewDocked && episodePreviewAspectRatio() != null) -dp(25).toFloat() else 0f
        val origin = episodePreviewOrigin
        if (previewDocked && origin != null && params.width > 0 && params.height > 0) {
            val remaining = 1f - episodeSheetProgress
            video.pivotX = params.width / 2f
            video.pivotY = 0f
            video.scaleX = 1f + (origin.width() / params.width - 1f) * remaining
            video.scaleY = 1f + (origin.height() / params.height - 1f) * remaining
            video.translationX = (origin.centerX() - page.width / 2f) * remaining
            video.translationY = (origin.top - params.topMargin) * remaining
        } else {
            video.scaleX = 1f; video.scaleY = 1f
            video.translationX = 0f; video.translationY = 0f
        }
        if (previewDocked) controls?.apply {
            alpha = if (uiHidden) 0f else 1f - episodeSheetProgress
            visibility = if (uiHidden || episodeSheetProgress == 1f) View.INVISIBLE else View.VISIBLE
        }
        updatePortraitFullscreenButton()
    }

    /** 横向视频的全屏入口贴着实际画面下沿，竖向视频与选集预览不显示。 */
    private fun updatePortraitFullscreenButton() {
        val button = portraitFullscreenButton ?: return
        val bounds = videoBoundsOnPage()
        if (fullscreen || videoAspectRatio <= 1f || episodeSheetTopOnScreen != null || bounds == null) {
            button.visibility = View.GONE
            return
        }
        val params = button.layoutParams as FrameLayout.LayoutParams
        val top = bounds.bottom.toInt() + dp(12)
        if (params.topMargin != top) {
            params.topMargin = top
            button.layoutParams = params
        }
        button.visibility = View.VISIBLE
    }

    private fun updateVideoLayout() {
        val page = host ?: return
        val video = videoHost ?: return
        val sheetTop = episodeSheetTopOnScreen
        var docked = false
        val params = if (sheetTop != null && episodePreviewAspectRatio() != null && page.width > 0 && page.height > 0) {
            val location = IntArray(2)
            page.getLocationOnScreen(location)
            val videoBottom = (sheetTop - location[1] - dp(8)).coerceIn(1, page.height)
            val availableHeight = (videoBottom - topInsetPx - dp(8)).coerceAtLeast(1)
            val videoHeight = minOf(page.width / videoAspectRatio, availableHeight.toFloat()).toInt().coerceAtLeast(1)
            val videoWidth = (videoHeight * videoAspectRatio).toInt().coerceIn(1, page.width)
            docked = true
            FrameLayout.LayoutParams(videoWidth, videoHeight, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = videoBottom - videoHeight
            }
        } else {
            FrameLayout.LayoutParams(-1, -1)
        }
        if (docked && !previewDocked && episodePreviewOrigin == null) {
            episodePreviewOrigin = videoBoundsOnPage()
        }
        val previous = video.layoutParams as FrameLayout.LayoutParams
        if (previous.width != params.width || previous.height != params.height ||
            previous.gravity != params.gravity || previous.topMargin != params.topMargin) {
            video.layoutParams = params
        }
        if (previewDocked != docked) {
            previewDocked = docked
            listOfNotNull(controls, topBar).forEach { layer ->
                layer.animate().cancel()
                layer.alpha = if (uiHidden) 0f else 1f
                layer.visibility = if (docked) View.INVISIBLE else if (uiHidden) View.GONE else View.VISIBLE
            }
            video.findViewWithTag<View>(TAG_DANMAKU)?.let { view ->
                (view.layoutParams as? FrameLayout.LayoutParams)?.topMargin = danmakuTopMargin()
                view.requestLayout()
            }
        }
        applyEpisodeSheetTransform()
    }

    /**
     * 弹幕层挂在视频区，上沿按皮肤走：
     *  - 竖屏：让出顶部标题栏（第三方参考实现同款），第一行弹幕不被返回键与倍速压住；
     *  - 横屏全屏：直接顶到顶部标题栏的位置（标题栏那一行控件占位），沉浸观看时
     *    控件整层淡出，弹幕就成为画面最上方的那一排。
     */
    fun attachDanmaku(view: View) {
        view.tag = TAG_DANMAKU
        videoHost?.addView(view, if (playerView == null) 0 else 1, FrameLayout.LayoutParams(-1, -1).apply { topMargin = danmakuTopMargin() })
        // 横屏全屏只在沉浸（控件隐藏）时显示弹幕
        if (fullscreen) view.visibility = if (uiHidden) View.VISIBLE else View.INVISIBLE
    }

    /** 弹幕层上边距（px）：横屏贴着标题栏顶部，竖屏让出标题栏整行。 */
    private fun danmakuTopMargin(): Int = if (fullscreen || previewDocked) 0 else dp(DANMAKU_TOP_DP) + topInsetPx

    /** 屏幕最底部离线状态条（含条上方的原因提示）：压在视频区底部，不挡控件。 */
    fun attachCacheStrip(view: View) {
        videoHost?.addView(view, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    /** 切集 / 详情刷新时更新标题、集号与简介；连续播放保持视频可见，不等待新的首帧回调。 */
    fun updateEpisode(data: Detail, waitForFirstFrame: Boolean = true) {
        resetSpeedBoost(restore = true)
        currentTitle = data.series.title
        currentDescription = data.series.description
        currentCover = data.series.cover
        episodeCount = data.episodes.size
        val number = activity.phoneEpisodeIndex + 1
        // 竖屏标题只写集号；横屏全屏按第三方参考实现写成「剧名 · 第N集」
        titleText?.text = if (fullscreen) "$currentTitle · 第 $number 集" else "第 $number 集"
        seriesTitleText?.text = currentTitle
        episodePickerText?.text = "选集 · 全${episodeCount}集"
        fsEpisodeButton?.text = "选集 · 全${episodeCount}集"
        // 文案严格对齐第三方参考实现 HongguoPlayerInfo：「第N集 · 共M集」/「第N集 | 简介」，集号不带空格
        introText?.text = if (currentDescription.isBlank()) "第${number}集 · 共${episodeCount}集"
            else "第${number}集 | $currentDescription"
        introText?.maxLines = if (introExpanded) 4 else 1
        // 简介为空时第三方参考实现不显示「展开」，这里跟着切
        introToggle?.visibility = if (currentDescription.isBlank()) View.GONE else View.VISIBLE
        val episodeId = data.episodes.getOrNull(activity.phoneEpisodeIndex)
        if (displayedEpisodeId != episodeId || episodePlaceholder == null) {
            displayedEpisodeId = episodeId
            videoHost?.let { video ->
                episodePlaceholder?.let(video::removeView)
                val placeholder = buildEpisodePlaceholder(activity.phoneEpisodeIndex, showInfo = false).apply {
                    visibility = if (waitForFirstFrame) View.VISIBLE else View.GONE
                }
                episodePlaceholder = placeholder
                val index = playerView?.let { video.indexOfChild(it) + 1 } ?: 0
                video.addView(placeholder, index, FrameLayout.LayoutParams(-1, -1))
            }
            lastPosition = 0L; lastDuration = 0L
            seekBar?.progress = 0
            centerPlay?.visibility = View.GONE
            errorBlock?.visibility = View.GONE
            buffering?.visibility = if (waitForFirstFrame) View.VISIBLE else View.GONE
        }
        episodePager?.updateEpisode(activity.phoneEpisodeIndex, episodeCount)
        updateSpeedBoostPosition()
    }

    /** 新集首帧到达后才撤掉封面，避免翻页时露出播放器保留的上一集画面。 */
    fun onFirstFrame() {
        episodePlaceholder?.visibility = View.GONE
        buffering?.visibility = View.GONE
    }

    /** 与第三方参考实现分页相同，用封面、集号和滑动提示承接相邻页与首帧等待。 */
    private fun buildEpisodePlaceholder(index: Int, showInfo: Boolean): View {
        val page = FrameLayout(activity).apply { setBackgroundColor(Color.rgb(0x19, 0x1F, 0x1C)) }
        val cover = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        page.addView(cover, FrameLayout.LayoutParams(-1, -1))
        loadCover(cover, currentCover)
        page.addView(View(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(92, 0, 0, 0), Color.argb(158, 0, 0, 0), Color.argb(128, 0, 0, 0)))
        }, FrameLayout.LayoutParams(-1, -1))
        val label = column().apply { gravity = Gravity.CENTER }
        label.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_play_arrow)
            setColorFilter(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(97, 0, 0, 0)) }
        }, rowLp(dp(58), dp(58)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        label.addView(text("第${index + 1}集", 22f).apply {
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }, rowLp(-1, -2).apply { topMargin = dp(10) })
        label.addView(text("上下滑动切换剧集", 12f, Color.argb(184, 255, 255, 255)).apply {
            gravity = Gravity.CENTER
        }, rowLp(-1, -2).apply { topMargin = dp(10) })
        page.addView(label, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        if (showInfo && !fullscreen) {
            val info = column().apply {
                setPadding(dp(16), dp(34), dp(16), dp(14))
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.argb(225, 0, 0, 0), Color.TRANSPARENT))
                visibility = if (uiHidden) View.INVISIBLE else View.VISIBLE
            }
            info.addView(text(currentTitle, 18f).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            }, rowLp(-1, dp(32)))
            info.addView(text(if (currentDescription.isBlank()) "第${index + 1}集 · 共${episodeCount}集"
                else "第${index + 1}集 | $currentDescription", 14f, Color.argb(184, 255, 255, 255)).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
            }, rowLp(-1, dp(32)))
            info.addView(text("选集 · 全${episodeCount}集", 15f).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                background = TvStyle.shape(activity, Color.rgb(0x15, 0x15, 0x15), radius = 10)
            }, rowLp(-1, dp(44)).apply { topMargin = dp(30); rightMargin = dp(48) })
            page.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            PhoneInsets.listen(page) { _, bottomInset, _ -> info.setPadding(dp(16), dp(34), dp(16), dp(14) + bottomInset) }
        }
        return page
    }

    /** 收藏态变化后刷新爱心图标。 */
    fun setFavorite(value: Boolean) {
        favorite = value
        favoriteButton?.setImageResource(if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        favoriteButton?.setColorFilter(if (favorite) 0xFFFF4D67.toInt() else Color.WHITE)
    }

    /** 播放状态变化时立即调用，定时刷新时同步进度。 */
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
        resetSpeedBoost(restore = true)
        if (fullscreen) activity.exitFullscreenPhone(rebuild = false, restoreOrientation = false)
        fullscreen = false
        clearRefs()
    }

    // ---------------- 控件构建 ----------------

    /**
     * 控件层按皮肤分两套：
     *  - 竖屏：[buildPortraitControls]，剧名 + 简介 + 进度条 + 选集行；
     *  - 横屏全屏：[buildFullscreenControls]，紧凑一行控制条，对应第三方参考实现 fullscreen 布局。
     * 两套共用顶部状态栏（横屏时标题多带剧名）。
     */
    /**
     * 长按倍速底部状态：落在原先剧集信息/收藏区域，
     * 「2.0×」黄色，后面的「倍速播放中」白色。点击整行可锁定/解锁。
     */
    private fun buildSpeedBoostOverlay(parent: FrameLayout) {
        val overlay = FrameLayout(activity).apply {
            visibility = View.GONE
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = row().apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = "2.0倍速播放中，点击锁定倍速"
            setOnClickListener {
                if (longPressLocked) unlockSpeedBoost() else lockSpeedBoost()
            }
        }
        label.addView(text("2.0× ", 18f, SPEED_BOOST_COLOR).apply {
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.argb(180, 0, 0, 0))
        }, rowLp(-2, -2))
        label.addView(text("倍速播放中", 18f, Color.WHITE).apply {
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.argb(180, 0, 0, 0))
        }, rowLp(-2, -2).apply { leftMargin = dp(2) })
        speedBoostLabel = label
        overlay.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        parent.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        speedBoostOverlay = overlay
    }

    private fun showSpeedBoostOverlay() {
        val overlay = speedBoostOverlay ?: return
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        speedBoostLabel?.contentDescription =
            if (longPressLocked) "2.0倍速已锁定，点击解锁并恢复原倍速" else "2.0倍速播放中，点击锁定倍速"
        updateSpeedBoostPosition()
    }

    private fun hideSpeedBoostOverlay() {
        speedBoostOverlay?.visibility = View.GONE
    }

    /** 长按倍速时隐藏播放页其余信息，只保留底部黄色倍速状态。 */
    private fun enterSpeedBoostImmersive() {
        captureSpeedBoostArea()
        uiHiddenBeforeSpeedBoost = uiHidden
        uiHidden = true
        listOfNotNull(controls, topBar).forEach { layer ->
            layer.animate().cancel()
            layer.visibility = View.GONE
            layer.alpha = 0f
        }
        portraitFullscreenButton?.visibility = View.GONE
        centerPlay?.visibility = View.GONE
        syncDanmakuWithControls()
        // 长按刚开始时播放器可能还没完成首帧测量，下一帧再校准底部位置。
        speedBoostOverlay?.post { updateSpeedBoostPosition() }
    }

    /** 松开长按后恢复播放页原有信息；锁定态继续沉浸，不提前露出控件。 */
    private fun restoreAfterSpeedBoost() {
        if (longPressLocked) return
        uiHidden = uiHiddenBeforeSpeedBoost
        syncDanmakuWithControls()
        listOfNotNull(controls, topBar).forEach { layer ->
            layer.animate().cancel()
            layer.visibility = View.VISIBLE
            layer.animate()
                .alpha(if (uiHidden) 0f else 1f)
                .setDuration(140)
                .withEndAction { if (uiHidden) layer.visibility = View.GONE }
                .start()
        }
        updatePortraitFullscreenButton()
        updateSpeedBoostPosition()
    }

    private fun startSpeedBoost() {
        if (!longPressFingerDown || longPressTriggered || longPressLocked) return
        if (!activity.phonePlaybackReady) return
        val before = activity.phonePlaybackSpeed()
        longPressTriggered = true
        longPressSpeedActive = true
        longPressSpeedBefore = before
        activity.setPhonePlaybackSpeed(SPEED_BOOST)
        // 先记录原信息内容区坐标，再隐藏控件；布局尚未完成时下一帧再执行，
        // 避免在 long press 瞬间读到 0 高内容区而退回到估算位置。
        val content = portraitInfoContent
        if (content != null && content.height > 0) {
            enterSpeedBoostImmersive()
            showSpeedBoostOverlay()
        } else {
            val pending = Runnable {
                speedBoostImmersivePending = null
                if (longPressTriggered && !longPressLocked && longPressFingerDown) {
                    enterSpeedBoostImmersive()
                    showSpeedBoostOverlay()
                }
            }
            speedBoostImmersivePending = pending
            portraitInfoContent?.post(pending)
        }
        speedBoostOverlay?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** 松手：已锁定则保留 2× 和沉浸界面；否则恢复原倍速与原界面。 */
    private fun finishSpeedBoost(restore: Boolean) {
        speedBoostImmersivePending?.let { portraitInfoContent?.removeCallbacks(it) }
        speedBoostImmersivePending = null
        cancelLongPress()
        longPressFingerDown = false
        longPressTriggered = false
        if (longPressLocked) {
            updateSpeedBoostPosition()
            return
        }
        if (longPressSpeedActive) {
            if (restore) activity.setPhonePlaybackSpeed(longPressSpeedBefore)
            longPressSpeedActive = false
        }
        hideSpeedBoostOverlay()
        restoreAfterSpeedBoost()
    }

    /** 离开播放页、切集或旋转重建前彻底结束长按会话，避免把临时状态带到新页面。 */
    private fun resetSpeedBoost(restore: Boolean) {
        val locked = longPressLocked
        val active = longPressSpeedActive
        cancelLongPress()
        longPressFingerDown = false
        longPressTriggered = false
        longPressLocked = false
        longPressSpeedActive = false
        if ((active || locked) && restore) activity.setPhonePlaybackSpeed(longPressSpeedBefore)
        hideSpeedBoostOverlay()
        if (active || locked) restoreAfterSpeedBoost()
    }

    private fun lockSpeedBoost() {
        if (!longPressSpeedActive) return
        longPressLocked = true
        showSpeedBoostOverlay()
    }

    /** 当前是否仍占用 2×，供 MainActivity 在尚未走到 UI 重建的释放路径里先恢复。 */
    fun hasActiveSpeedBoost(): Boolean = longPressSpeedActive || longPressLocked

    /** 自然连播复用同一个播放器：切集前先恢复原倍速，再让新集按用户设置起播。 */
    fun restoreSpeedBeforeEpisodeChange() {
        val locked = longPressLocked
        val active = longPressSpeedActive
        cancelLongPress()
        longPressFingerDown = false
        longPressTriggered = false
        longPressLocked = false
        longPressSpeedActive = false
        if (active || locked) activity.setPhonePlaybackSpeed(longPressSpeedBefore)
        hideSpeedBoostOverlay()
        if (active || locked) restoreAfterSpeedBoost()
    }

    private fun unlockSpeedBoost() {
        val restore = longPressSpeedActive || longPressLocked
        longPressLocked = false
        longPressSpeedActive = false
        longPressTriggered = false
        if (restore) activity.setPhonePlaybackSpeed(longPressSpeedBefore)
        hideSpeedBoostOverlay()
        restoreAfterSpeedBoost()
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { activity.window.decorView.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun beginLongPress(event: MotionEvent) {
        if (!longPressFingerDown) return
        longPressDownX = event.x
        longPressDownY = event.y
        val runnable = Runnable {
            longPressRunnable = null
            startSpeedBoost()
        }
        longPressRunnable = runnable
        activity.window.decorView.postDelayed(runnable, LONG_PRESS_MS)
    }

    private fun handleLongPressOutside(event: MotionEvent): Boolean {
        if (!longPressFingerDown) return false
        val moved = kotlin.math.abs(event.x - longPressDownX) > dp(10) ||
            kotlin.math.abs(event.y - longPressDownY) > dp(10)
        if (moved && !longPressTriggered) {
            // 未达到长按阈值就滑走：立即取消计时，并交给外层翻页。
            cancelLongPress()
            longPressFingerDown = false
            hideSpeedBoostOverlay()
            return false
        }
        return longPressTriggered
    }

    private fun buildControls(page: FrameLayout) {
        val layer = FrameLayout(activity)
        controls = layer
        page.addView(layer, FrameLayout.LayoutParams(-1, -1))
        if (fullscreen) buildFullscreenControls(layer) else buildPortraitControls(layer)
    }

    /** 顶部状态栏：返回 / 标题 / 倍速 / 更多（第三方参考实现同构，两套皮肤共用）。 */
    private fun buildTopBar(layer: FrameLayout) {
        val top = row().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(178, 0, 0, 0), Color.TRANSPARENT))
            setPadding(dp(4), dp(8), dp(6), dp(24))
        }
        topBar = top
        // 全屏横屏时返回键即「退出全屏」，与第三方参考实现一致
        top.addView(iconButton(R.drawable.ic_back, 26, 44) {
            if (fullscreen) exitFullscreenSkin() else activity.returnToDetailPhone()
        }, rowLp(dp(44), dp(48)))
        titleText = text("", 18f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        }
        top.addView(titleText, rowLp(0, -2).apply { weight = 1f; leftMargin = dp(2) })

        // 倍速：时钟图标 + 「1×」文字，点击弹出本剧倍速选择器（与第三方参考实现一致）
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

    /** 竖屏皮肤底部：剧集信息 + 进度条 + 选集行（第三方参考实现 HongguoPlayerInfo + 选集按钮）。 */
    private fun buildPortraitControls(layer: FrameLayout) {
        val bottomBar = column().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(225, 0, 0, 0), Color.TRANSPARENT))
            setPadding(0, dp(34), 0, dp(8))
        }
        bottom = bottomBar

        val info = column().apply { setPadding(dp(16), 0, dp(16), 0) }
        portraitInfoContent = info
        val titleRow = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(2))
            setOnClickListener { activity.showPhoneEpisodePicker() }
        }
        seriesTitleText = text("", 18f).apply {
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            // 第三方参考实现 HongguoPlayerInfo：lineHeight 26sp → 行距 = 26 - 18 = 8sp 额外间距
            setLineSpacing(dp(8).toFloat(), 1f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        // 内容容器随剧名收缩，让箭头紧跟标题；空间不足时由标题权重让出箭头宽度。
        val titleContent = row()
        titleContent.addView(seriesTitleText, rowLp(0, -2).apply { weight = 1f })
        titleContent.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_chevron_right); setColorFilter(Color.WHITE)
        }, rowLp(dp(20), dp(20)).apply { marginStart = dp(2) })
        titleRow.addView(titleContent, rowLp(-2, -2))
        // heightIn(min = 32dp)，内容垂直居中
        info.addView(titleRow, rowLp(-1, dp(32)))

        val introRow = row()
        introText = text("", 14f, Color.argb(184, 255, 255, 255)).apply {
            // 第三方参考实现 HongguoPlayerInfo：lineHeight 22sp → 16sp
            setLineSpacing(dp(8).toFloat(), 1f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            // padding(vertical = 4dp)
            setPadding(0, dp(4), 0, dp(4))
        }
        introRow.addView(introText, rowLp(0, -2).apply { weight = 1f })
        // 第三方参考实现：只有简介非空才出现「展开 / 收起」按钮
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

        // 进度条：白色细线 + 小圆点（第三方参考实现 HongguoSeekBar 外观）
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

        // 「选集 · 全N集」整行按钮 + 收藏；全屏入口独立放到横向视频下方。
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

        bottomBar.addView(actions, rowLp(-1, -2))

        layer.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        val watchFullscreen = row().apply {
            gravity = Gravity.CENTER
            background = TvStyle.shape(activity, Color.rgb(0x20, 0x20, 0x20), radius = 8)
            setPadding(dp(12), 0, dp(14), 0)
            contentDescription = "全屏观看，切换横屏"
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { enterFullscreenFromButton() }
        }
        watchFullscreen.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_screen_rotate)
            setColorFilter(Color.WHITE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, rowLp(dp(15), dp(15)).apply { rightMargin = dp(6) })
        watchFullscreen.addView(text("全屏观看", 10f).apply { maxLines = 1 }, rowLp(-2, -2))
        portraitFullscreenButton = watchFullscreen
        layer.addView(watchFullscreen, FrameLayout.LayoutParams(-2, dp(32), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    /**
     * 横屏全屏皮肤底部：进度条 + 一行紧凑控制条（第三方参考实现 fullscreen 布局）。
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

    /** 切换全屏后同步入口位置、退出图标与播放进度。 */
    private fun applyFullscreenUi() {
        updateVideoLayout()
        fullscreenButton?.setImageResource(if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        playPauseButton?.setImageResource(if (lastPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        timeText?.text = "${formatTime(lastPosition)} / ${formatTime(lastDuration)}"
    }

    // ---------------- 触摸 ----------------

    @SuppressLint("ClickableViewAccessibility")
    private val touchListener = View.OnTouchListener { _, event ->
        handleVideoTouch(event)
        true
    }

    /** 视频区触摸：长按计时与原有轻点/双击共用同一套事件，长按触发后吞掉抬手。 */
    private fun handleVideoTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressFingerDown = true
                longPressTriggered = false
                beginLongPress(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (longPressTriggered) {
                    // 长按期间的手指抖动不再参与轻点/双击识别。
                    return
                }
                if (!handleLongPressOutside(event)) {
                    // 已判定为滑动后，同一手势后续的 MOVE 继续交给分页。
                    gesture.onTouchEvent(event)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasBoosted = longPressTriggered || longPressLocked
                cancelLongPress()
                longPressFingerDown = false
                finishSpeedBoost(restore = true)
                // 长按已经消费这次抬手，不能再让 GestureDetector 判成轻点。
                if (!wasBoosted) gesture.onTouchEvent(event)
            }
            else -> if (!longPressTriggered) gesture.onTouchEvent(event)
        }
    }

    // 分页手势由外层 PhoneEpisodePager 拦截，视频区仅处理轻点和双击。
    private val gesture: GestureDetector by lazy {
        GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

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
        if (controls == null) return
        uiHidden = !uiHidden
        // 横屏全屏的弹幕跟着控件一起进出：控件在时让位，沉浸时才铺到标题栏位置
        syncDanmakuWithControls()
        listOfNotNull(controls, topBar).forEach { layer ->
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
     * 退出全屏：转回竖屏并回到竖屏皮肤（第三方参考实现的退出逻辑 → fullscreen = false）。
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

    /** 返回键在全屏播放时退出全屏（与第三方参考实现相同），由 MainActivity 的 goBack 调用。 */
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
        episodePager?.release(); episodePager = null
        controls?.animate()?.cancel(); topBar?.animate()?.cancel()
        episodePlaceholder = null; displayedEpisodeId = null
        host = null; videoHost = null; playerView = null; controls = null
        videoAspectRatio = 0f; episodeSheetTopOnScreen = null; previewDocked = false
        episodeSheetProgress = 0f; episodePreviewOrigin = null
        topInsetPx = 0
        topBar = null; bottom = null; titleText = null; speedLabel = null
        seriesTitleText = null; introText = null; introToggle = null; episodePickerText = null
        playPauseButton = null; timeText = null; fsEpisodeButton = null
        seekBar = null; favoriteButton = null; fullscreenButton = null; portraitFullscreenButton = null
        centerPlay = null; buffering = null; errorBlock = null; errorText = null
        speedBoostImmersivePending?.let { portraitInfoContent?.removeCallbacks(it) }
        speedBoostImmersivePending = null
        speedBoostOverlay = null; speedBoostLabel = null; portraitInfoContent = null
        speedBoostAreaTop = 0; speedBoostAreaBottom = 0
        cancelLongPress(); longPressFingerDown = false; longPressTriggered = false
        longPressLocked = false; longPressSpeedActive = false
    }

    private companion object {
        /** 弹幕层上沿：标题栏高度（dp），与顶部栏的视觉高度对齐。 */
        const val DANMAKU_TOP_DP = 52
        /** 长按触发阈值与固定倍速。 */
        const val LONG_PRESS_MS = 420L
        const val SPEED_BOOST = 2f
        val SPEED_BOOST_COLOR = Color.rgb(255, 214, 64)
        /** 弹幕层的 view tag：Insets 变化后按它找回来重设上边距。 */
        const val TAG_DANMAKU = "phone-danmaku-layer"
    }
}
