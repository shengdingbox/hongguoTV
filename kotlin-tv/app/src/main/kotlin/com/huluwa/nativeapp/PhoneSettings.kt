// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.hongguotv.core.DanmakuPrefs
import com.hongguotv.core.PlaybackSpeed

/**
 * 手机竖屏设置页：把电视版设置里对手机有意义的项按「播放 / 外观 / 设备 / 实验室 / 关于」
 * 重新排成单列，竖屏下就能改完全部设置，不必再旋转到横屏。
 *
 * 页面本身只负责排版：取值、落盘、弹窗一律复用 MainActivity 既有的实现
 * （见 [MainActivity.showPhoneSettings] 附近的 phoneXxx 桥接方法），避免两套设置逻辑各写一份。
 * 每行右侧的当前值都登记成 [Binding]，弹窗关闭后由 Activity 窗口重新拿到焦点时调用
 * [refreshValues] 就地更新——不整页重建，滚动位置因此不会跳回顶部。
 */
class PhoneSettings(private val activity: MainActivity) {

    private val library get() = activity.phoneLibrary

    /** 一行设置右侧的值：provider 每次实时读库，弹窗改完只刷新文字。 */
    private class Binding(val provider: () -> String?, val view: TextView)

    private val bindings = mutableListOf<Binding>()
    /** 整页重画（切主题等）时保留滚动位置：设置项多，滚回顶部会让人找不到刚才改的地方。 */
    private var lastScrollY = 0

    private fun dp(v: Int) = PhoneUi.dp(activity, v)
    private fun text(v: String, size: Float, color: Int = TvStyle.text) = PhoneUi.text(activity, v, size, color)

    fun render(root: FrameLayout) {
        if (!library.isLoaded) return
        // 顶栏是浅色时状态栏图标要跟着变深，否则暖玉白主题下白图标看不见
        activity.phoneStatusBarFollowTheme()
        root.removeAllViews()
        root.setBackgroundColor(TvStyle.background)
        bindings.clear()

        val shell = PhoneUi.column(activity)
        val topBaseTop = dp(8)
        val baseHorizontal = dp(14)
        val baseBottom = dp(18)
        val top = PhoneUi.row(activity).apply { setPadding(baseHorizontal, topBaseTop, dp(14), dp(8)) }
        PageBackButton.addTo(top) { activity.closePhoneSettings() }
        top.addView(text("设置", 20f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, PhoneUi.lp(0, -2).apply { weight = 1f })
        shell.addView(top, PhoneUi.lp(-1, -2))

        val scroll = NestedScrollView(activity).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        val content = PhoneUi.column(activity).apply { setPadding(dp(12), 0, dp(12), dp(18)) }
        scroll.addView(content, PhoneUi.lp(-1, -2))
        shell.addView(scroll, PhoneUi.lp(-1, 0).apply { weight = 1f })

        playbackSection(content)
        lookSection(content)
        deviceSection(content)
        labSection(content)
        aboutSection(content)

        // 系统栏让位：顶栏避开状态栏，内容底部避开手势条，否则最后一行会被压在导航条下面
        PhoneInsets.listen(shell) { topInset, bottomInset, _ ->
            top.setPadding(baseHorizontal, topBaseTop + topInset, dp(14), dp(8))
            content.setPadding(dp(12), 0, dp(12), baseBottom + bottomInset)
        }
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))
        // 重画后回到原来的位置（首次进入为 0）
        val restoreY = lastScrollY
        scroll.post { if (restoreY > 0 && scroll.scrollY != restoreY) scroll.scrollTo(0, restoreY) }
        scroll.viewTreeObserver.addOnScrollChangedListener { lastScrollY = scroll.scrollY }
    }

    /** 弹窗改完某一项后只刷新右侧值，不重建页面（否则会滚回顶部）。 */
    fun refreshValues() {
        if (bindings.isEmpty()) return
        bindings.forEach { binding ->
            val next = binding.provider().orEmpty()
            if (binding.view.text.toString() != next) binding.view.text = next
        }
    }

    // ---- 分组与行 ----

    private fun heading(parent: LinearLayout, title: String, subtitle: String? = null) {
        parent.addView(text(title, 13f, TvStyle.muted).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(4), dp(16), dp(4), dp(2))
        }, PhoneUi.lp(-1, -2))
        if (subtitle != null) {
            parent.addView(text(subtitle, 11f, TvStyle.muted).apply { setPadding(dp(4), 0, dp(4), dp(8)) }, PhoneUi.lp(-1, -2))
        }
    }

    /**
     * 一行设置：左标题 + 右当前值 + 箭头，整行可点。
     * [value] 传 null 表示右侧不显示值（如「开源许可」）；[provider] 用于弹窗关闭后就地刷新。
     */
    private fun row(label: String, value: String?, provider: (() -> String?)? = null, onClick: () -> Unit): LinearLayout {
        val line = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 标题、右侧值与箭头字号不同；关闭基线对齐后统一按行高垂直居中，
            // 避免带右侧值的行（如「观看统计」）标题看起来偏上/偏下。
            isBaselineAligned = false
            setPadding(dp(16), dp(13), dp(13), dp(13))
            background = TvStyle.shape(activity, TvStyle.surface)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            // 弹窗改完焦点回到这一行：顺手把整页取值刷一遍（遥控键盘/电视遥控走这条）
            setOnFocusChangeListener { _, focused -> if (focused) refreshValues() }
        }
        line.addView(text(label, 15f).apply {
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }, PhoneUi.lp(0, -2).apply { weight = 1f })
        if (value != null) {
            val valueView = text(value, 13f, TvStyle.muted).apply {
                maxLines = 1
                maxWidth = dp(160)
                ellipsize = TextUtils.TruncateAt.END
            }
            line.addView(valueView)
            provider?.let { bindings += Binding(it, valueView) }
        }
        line.addView(text("›", 16f, TvStyle.muted), PhoneUi.lp(-2, -2).apply { leftMargin = dp(7) })
        return line
    }

    /** 开关行：值文案由传入的 provider 实时给出。 */
    private fun toggleRow(label: String, provider: () -> Boolean, onClick: () -> Unit): LinearLayout =
        row(label, if (provider()) "开启" else "关闭", { if (provider()) "开启" else "关闭" }, onClick)

    private fun addRow(parent: LinearLayout, line: LinearLayout) {
        parent.addView(line, PhoneUi.lp(-1, -2).apply { bottomMargin = dp(7) })
    }

    // ---- 播放与观看 ----

    private fun playbackSection(parent: LinearLayout) {
        heading(parent, "播放与观看", "所有剧集共用，播放中也可调整")
        addRow(parent, row("默认清晰度", "${library.maxQuality}P", { "${library.maxQuality}P" }) {
            activity.phoneQualityPicker()
        })
        addRow(parent, row("默认倍速", PlaybackSpeed.label(library.playbackSpeed), { PlaybackSpeed.label(library.playbackSpeed) }) {
            activity.phoneSpeedPicker()
        })
        addRow(parent, row("画面模式", library.frameMode.label, { library.frameMode.label }) {
            activity.phoneFramePicker()
        })
        addRow(parent, toggleRow("自动连播", { library.autoNext }) {
            library.autoNext = !library.autoNext
            activity.phoneRefreshSettingsValues()
        })
        addRow(parent, toggleRow("系列剧连播", { library.autoSeason }) {
            library.autoSeason = !library.autoSeason
            activity.phoneRefreshSettingsValues()
        })
        addRow(parent, toggleRow("整集预载缓存", { library.episodeCache }) {
            library.episodeCache = !library.episodeCache
            activity.phoneApplyEpisodeCache()
            activity.phoneRefreshSettingsValues()
        })
        addRow(parent, toggleRow("官方弹幕", { library.danmaku }) {
            library.danmaku = !library.danmaku
            activity.phoneApplyDanmakuPrefs()
            activity.phoneRefreshSettingsValues()
        })
        // 弹幕细分项直接摊在页面上：竖屏只有一列，逐项点开比套一层二级菜单顺手
        addRow(parent, row("弹幕不透明度", "${library.danmakuOpacity}%", { "${library.danmakuOpacity}%" }) {
            activity.phoneDanmakuOpacity()
        })
        addRow(parent, row("弹幕字体大小", DanmakuPrefs.fontLabel(library.danmakuFont), { DanmakuPrefs.fontLabel(library.danmakuFont) }) {
            activity.phoneDanmakuFont()
        })
        addRow(parent, row("弹幕移动速度", DanmakuPrefs.speedLabel(library.danmakuSpeed), { DanmakuPrefs.speedLabel(library.danmakuSpeed) }) {
            activity.phoneDanmakuSpeed()
        })
        addRow(parent, row("弹幕展示行数", "${library.danmakuRows} 行", { "${library.danmakuRows} 行" }) {
            activity.phoneDanmakuRows()
        })
        addRow(parent, row("弹幕屏蔽词", "${activity.phoneBlockedWordCount()} 个", { "${activity.phoneBlockedWordCount()} 个" }) {
            activity.phoneBlockedWords()
        })
        addRow(parent, toggleRow("不兼容片源自动跳过", { activity.phoneAutoSkip }) {
            activity.phoneSetAutoSkip(!activity.phoneAutoSkip)
            activity.phoneRefreshSettingsValues()
        })
        addRow(parent, row("恢复弹幕默认设置", null) { activity.phoneResetDanmakuPrefs() })
    }

    // ---- 外观与界面 ----

    private fun lookSection(parent: LinearLayout) {
        heading(parent, "外观与界面", "主题与屏幕方向")
        addRow(parent, row("系统风格", TvStyle.theme.label, { TvStyle.theme.label }) { activity.phoneThemeToggle() })
        addRow(parent, row("界面缩放", UiScale.label(activity), { UiScale.label(activity) }) { activity.phoneUiScalePicker() })
        addRow(parent, row("启动方向", OrientationMode.of(library.orientationMode).label, {
            OrientationMode.of(library.orientationMode).label
        }) { activity.phoneOrientationPicker() })
        if (OrientationMode.of(library.orientationMode) == OrientationMode.LANDSCAPE) {
            addRow(parent, row("首页版本", HomeVersion.of(library.homeVersion).label, {
                HomeVersion.of(library.homeVersion).label
            }) { activity.phoneHomeVersionPicker() })
        }
    }

    // ---- 设备与数据 ----

    private fun deviceSection(parent: LinearLayout) {
        heading(parent, "设备与数据", "本机记录、存储与快传文件")
        val account = activity.phoneAccount
        // 昵称与云端条数分开取，登录态变化由 MainActivity.onAccountChanged 整页刷新
        addRow(parent, row(
            if (account.signedIn) "红果账号 · ${account.displayName}" else "红果账号",
            if (account.signedIn) "云端 ${account.cloudCount} 部" else "点击登录",
        ) { account.showPhoneAccountDialog(activity) })
        addRow(parent, row("本地备份与恢复", if (library.localBackupEnabled) "已开启" else "已关闭", {
            if (library.localBackupEnabled) "已开启" else "已关闭"
        }) { activity.phoneLocalBackup() })
        addRow(parent, row("手机快传", "传 APK / 视频") { activity.phoneQuickTransfer() })
        addRow(parent, row("快传文件", "查看已收到的文件") { activity.phoneQuickFiles() })
        addRow(parent, row("恢复隐藏推荐", "${library.hidden().size} 部", { "${library.hidden().size} 部" }) {
            activity.phoneUnhideAll()
        })
        addRow(parent, row("观看统计", "本机累计") { activity.phoneWatchStats() })
        addRow(parent, row("清理缓存", "封面与安装包等临时文件") { activity.phoneClearCache() })
    }

    // ---- 实验室功能 ----

    private fun labSection(parent: LinearLayout) {
        heading(parent, "实验室功能", "外部遥控、语音与自动化接口")
        addRow(parent, row("遥控地址", "自动化 / 语音桥接") { activity.phoneRemoteAddress() })
        addRow(parent, row("小爱同学", if (XiaoAiAccess.uid.isBlank()) "未连接" else "已配置 ${XiaoAiAccess.masked()}") {
            activity.phoneXiaoAi()
        })
    }

    // ---- 关于红果 ----

    private fun aboutSection(parent: LinearLayout) {
        heading(parent, "关于红果", "版本更新、接口自检与许可")
        addRow(parent, row("版本与更新", BuildConfig.VERSION_NAME) { activity.phoneUpdater() })
        addRow(parent, row("接口自检", "首页 / 搜索 / 播放") { activity.phoneInterfaceCheck() })
        addRow(parent, row("运行日志", "预载缓存 · 一键复制") { activity.phoneRuntimeLogs() })
        addRow(parent, row("异常日志", "${CrashReporter.count(activity)} 份", { "${CrashReporter.count(activity)} 份" }) {
            activity.phoneCrashLogs()
        })
        addRow(parent, row("开源许可", null) {
            TvDialog.info(activity, "开源许可", "本原生版以 GPL-3.0 发布。\n内容协议与加密处理移植自 drpys（22261ad）。\n请求签名算法移植自 openhongguo（GPL-3.0），在本机完成计算与匿名设备注册。\nAndroidX Media3 / OkHttp：Apache-2.0\nKotlin：Apache-2.0\n完整源码和许可证见 GitHub：jycao2/hongguoTV。").show()
        })

        parent.addView(text("红果  /  ${BuildConfig.VERSION_NAME}    ·    收藏与本机记录保存在设备 · 登录后可同步到红果账号", 12f, TvStyle.muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(18), dp(8), dp(4))
        }, PhoneUi.lp(-1, -2))
        parent.addView(text("本应用仅用于技术交流与学习，不提供、不存储、不传播任何视频内容，内容版权归原平台所有。", 11f, TvStyle.muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), dp(8))
        }, PhoneUi.lp(-1, -2))
    }
}
