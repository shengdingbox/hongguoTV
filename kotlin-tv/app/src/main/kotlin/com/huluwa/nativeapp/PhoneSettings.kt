// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.hongguotv.core.DanmakuPrefs
import com.hongguotv.core.PlaybackSpeed

/** 手机竖屏的 iOS 分组设置页；设置取值、落盘与操作仍复用原有桥接。 */
class PhoneSettings(private val activity: MainActivity) {
    private val library get() = activity.phoneLibrary
    private val pageColor get() = if (PortraitStyle.isDark) Color.BLACK else Color.rgb(242, 242, 247)
    private val groupColor get() = if (PortraitStyle.isDark) Color.rgb(28, 28, 30) else Color.WHITE
    private val secondaryColor get() = if (PortraitStyle.isDark) Color.rgb(152, 152, 159) else Color.rgb(109, 109, 114)
    private val separatorColor get() = if (PortraitStyle.isDark) Color.rgb(56, 56, 59) else Color.rgb(218, 218, 222)
    private val accent get() = PortraitStyle.navigationSelected

    private class Binding(val provider: () -> String?, val view: TextView)
    private class ToggleBinding(val provider: () -> Boolean, val view: PhoneSettingsSwitch)
    private val bindings = mutableListOf<Binding>()
    private val toggles = mutableListOf<ToggleBinding>()
    private var refreshingValues = false
    private var lastScrollY = 0
    private var activeScroll: NestedScrollView? = null

    private fun dp(value: Int) = PhoneUi.dp(activity, value)
    private fun text(value: String, size: Float = 17f, color: Int = PortraitStyle.text) = PhoneUi.text(activity, value, size, color)
    private fun lp(w: Int = -1, h: Int = -2) = LinearLayout.LayoutParams(w, h)

    fun render(root: FrameLayout) {
        if (!library.isLoaded) return
        val restoreY = lastScrollY
        PortraitStyle.refresh(activity)
        activity.phoneStatusBarLight(!PortraitStyle.isDark)
        activeScroll = null
        root.removeAllViews()
        root.setBackgroundColor(pageColor)
        bindings.clear()
        toggles.clear()

        val shell = PhoneUi.column(activity)
        val navigation = FrameLayout(activity).apply { setBackgroundColor(pageColor) }
        val bar = FrameLayout(activity)
        val back = PhoneUi.row(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(12), 0)
            background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 12)
            contentDescription = "返回"
            isFocusable = true
            setOnClickListener { activity.closePhoneSettings() }
        }
        back.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_page_back)
            setColorFilter(accent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, lp(dp(13), dp(22)))
        back.addView(text("返回", 17f, accent).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, lp(-2, -2).apply { leftMargin = dp(5) })
        bar.addView(back, FrameLayout.LayoutParams(-2, -1, Gravity.START))
        val inlineTitle = text("设置", 17f).apply {
            setTypeface(null, Typeface.BOLD)
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        bar.addView(inlineTitle, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        navigation.addView(bar, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))
        val hairline = View(activity).apply { setBackgroundColor(separatorColor); alpha = 0f }
        navigation.addView(hairline, FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM))
        shell.addView(navigation, lp(-1, dp(48)))

        val scroll = NestedScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            clipToPadding = false
        }
        activeScroll = scroll
        val content = PhoneUi.column(activity).apply { setPadding(dp(20), 0, dp(20), dp(24)) }
        content.addView(text("设置", 34f).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = -.025f
            setPadding(0, dp(4), 0, dp(20))
            accessibilityHeadingCompat()
        }, lp())
        accountCard(content)
        playbackSection(content)
        danmakuSection(content)
        lookSection(content)
        deviceSection(content)
        aboutSection(content)
        scroll.addView(content, lp())
        shell.addView(scroll, lp(-1, 0).apply { weight = 1f })
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))

        fun updateNavigation() {
            val progress = ((scroll.scrollY - dp(24)).toFloat() / dp(32).coerceAtLeast(1)).coerceIn(0f, 1f)
            inlineTitle.alpha = progress
            hairline.alpha = progress
        }
        PhoneInsets.listen(shell) { topInset, bottomInset, _ ->
            navigation.layoutParams = lp(-1, dp(48) + topInset)
            navigation.setPadding(0, topInset, 0, 0)
            content.setPadding(dp(20), 0, dp(20), dp(24) + bottomInset)
        }
        scroll.viewTreeObserver.addOnScrollChangedListener {
            if (activeScroll === scroll) {
                lastScrollY = scroll.scrollY
                updateNavigation()
            }
        }
        scroll.post {
            if (activeScroll === scroll) {
                scroll.scrollTo(0, restoreY)
                lastScrollY = scroll.scrollY
                updateNavigation()
            }
        }
    }

    private fun View.accessibilityHeadingCompat() {
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
    }

    /** 弹窗关闭或开关变更时就地同步，保留当前滚动位置。 */
    fun refreshValues() {
        if (refreshingValues) return
        refreshingValues = true
        try {
            bindings.forEach { binding ->
                val next = binding.provider().orEmpty()
                if (binding.view.text.toString() != next) binding.view.text = next
            }
            toggles.forEach { binding -> binding.view.isChecked = binding.provider() }
        } finally {
            refreshingValues = false
        }
    }

    private fun chevron() = ImageView(activity).apply {
        setImageResource(R.drawable.ic_chevron_right)
        setColorFilter(secondaryColor)
        alpha = .65f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun card() = PhoneUi.column(activity).apply {
        background = PortraitStyle.shape(activity, groupColor, radius = 12)
        clipToOutline = true
    }

    private fun section(parent: LinearLayout, title: String, footer: String? = null, build: (LinearLayout) -> Unit) {
        parent.addView(text(title, 13f, secondaryColor).apply {
            setPadding(dp(16), dp(8), dp(16), dp(7))
            accessibilityHeadingCompat()
        }, lp())
        val group = card()
        build(group)
        parent.addView(group, lp())
        if (footer != null) parent.addView(text(footer, 12f, secondaryColor).apply {
            setPadding(dp(16), dp(7), dp(16), 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }, lp())
        parent.addView(View(activity), lp(-1, dp(20)))
    }

    private fun addRow(parent: LinearLayout, line: LinearLayout) {
        if (parent.childCount > 0) parent.addView(View(activity).apply {
            setBackgroundColor(separatorColor)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, lp(-1, 1).apply { leftMargin = dp(16) })
        parent.addView(line, lp())
    }

    private fun baseRow(onClick: (() -> Unit)?): LinearLayout = PhoneUi.row(activity).apply {
        isBaselineAligned = false
        minimumHeight = dp(52)
        setPadding(dp(16), dp(10), dp(14), dp(10))
        background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 0)
        isClickable = onClick != null
        isFocusable = onClick != null
        if (onClick != null) setOnClickListener { onClick() }
        setOnFocusChangeListener { _, focused -> if (focused) refreshValues() }
    }

    private fun row(label: String, value: String? = null, provider: (() -> String?)? = null,
        actionColor: Int? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val line = baseRow(onClick)
        line.addView(text(label, color = actionColor ?: PortraitStyle.text).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, lp(0, -2).apply { weight = 1f })
        if (value != null) {
            val current = text(value, 16f, secondaryColor).apply {
                maxLines = 1
                maxWidth = dp(136)
                ellipsize = TextUtils.TruncateAt.END
            }
            line.addView(current, lp(-2, -2).apply { leftMargin = dp(10) })
            provider?.let { bindings += Binding(it, current) }
        }
        if (onClick != null && actionColor == null) line.addView(chevron(), lp(dp(12), dp(17)).apply { leftMargin = dp(8) })
        return line
    }

    private fun toggleRow(label: String, provider: () -> Boolean, onClick: () -> Unit): LinearLayout {
        val toggle = PhoneSettingsSwitch(activity).apply {
            isChecked = provider()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnCheckedChangeListener { _, checked ->
                if (!refreshingValues && checked != provider()) {
                    onClick()
                    refreshValues()
                }
            }
        }
        toggles += ToggleBinding(provider, toggle)
        return baseRow { toggle.performClick() }.apply {
            setPadding(dp(16), dp(4), dp(12), dp(4))
            addView(text(label).apply { maxLines = 2 }, lp(0, -2).apply { weight = 1f; rightMargin = dp(12) })
            addView(toggle, lp(dp(55), dp(44)))
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Switch"
                    info.isCheckable = true
                    info.isChecked = toggle.isChecked
                    info.text = label
                }
            }
        }
    }

    private fun accountCard(parent: LinearLayout) {
        val account = activity.phoneAccount
        val group = card()
        val line = baseRow { account.showPhoneAccountDialog(activity) }.apply {
            minimumHeight = dp(88)
            setPadding(dp(16), dp(14), dp(14), dp(14))
        }
        line.addView(PhoneSettingsAvatar(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, lp(dp(56), dp(56)).apply { rightMargin = dp(14) })
        val labels = PhoneUi.column(activity)
        labels.addView(text(if (account.signedIn) account.displayName else "红果账号", 20f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp())
        labels.addView(text(if (account.signedIn) "云端观看记录 · ${account.cloudCount} 部" else "登录与管理云端观看记录", 13f, secondaryColor).apply {
            maxLines = 2
            setPadding(0, dp(4), 0, 0)
        }, lp())
        line.addView(labels, lp(0, -2).apply { weight = 1f })
        line.addView(chevron(), lp(dp(12), dp(17)).apply { leftMargin = dp(8) })
        group.addView(line, lp())
        parent.addView(group, lp().apply { bottomMargin = dp(24) })
    }

    private fun playbackSection(parent: LinearLayout) {
        section(parent, "播放与观看", "默认设置适用于所有剧集。缓存后续三集会占用设备存储空间。") { group ->
            addRow(group, row("默认清晰度", "${library.maxQuality}P", { "${library.maxQuality}P" }) { activity.phoneQualityPicker() })
            addRow(group, row("默认倍速", PlaybackSpeed.label(library.playbackSpeed), { PlaybackSpeed.label(library.playbackSpeed) }) { activity.phoneSpeedPicker() })
            addRow(group, row("画面模式", library.frameMode.label, { library.frameMode.label }) { activity.phoneFramePicker() })
            addRow(group, toggleRow("自动连播", { library.autoNext }) {
                library.autoNext = !library.autoNext
                activity.phoneRefreshSettingsValues()
            })
            addRow(group, toggleRow("系列剧连播", { library.autoSeason }) {
                library.autoSeason = !library.autoSeason
                activity.phoneRefreshSettingsValues()
            })
            addRow(group, toggleRow("缓存后三集", { library.episodeCache }) {
                library.episodeCache = !library.episodeCache
                activity.phoneApplyEpisodeCache()
                activity.phoneRefreshSettingsValues()
            })
            addRow(group, toggleRow("不兼容片源自动跳过", { activity.phoneAutoSkip }) {
                activity.phoneSetAutoSkip(!activity.phoneAutoSkip)
                activity.phoneRefreshSettingsValues()
            })
        }
    }

    private fun danmakuSection(parent: LinearLayout) {
        section(parent, "弹幕", "调整官方弹幕的显示效果，屏蔽不想看到的内容。") { group ->
            addRow(group, toggleRow("官方弹幕", { library.danmaku }) {
                library.danmaku = !library.danmaku
                activity.phoneApplyDanmakuPrefs()
                activity.phoneRefreshSettingsValues()
            })
            addRow(group, row("不透明度", "${library.danmakuOpacity}%", { "${library.danmakuOpacity}%" }) { activity.phoneDanmakuOpacity() })
            addRow(group, row("字体大小", DanmakuPrefs.fontLabel(library.danmakuFont), { DanmakuPrefs.fontLabel(library.danmakuFont) }) { activity.phoneDanmakuFont() })
            addRow(group, row("移动速度", DanmakuPrefs.speedLabel(library.danmakuSpeed), { DanmakuPrefs.speedLabel(library.danmakuSpeed) }) { activity.phoneDanmakuSpeed() })
            addRow(group, row("展示行数", "${library.danmakuRows} 行", { "${library.danmakuRows} 行" }) { activity.phoneDanmakuRows() })
            addRow(group, row("屏蔽词", "${activity.phoneBlockedWordCount()} 个", { "${activity.phoneBlockedWordCount()} 个" }) { activity.phoneBlockedWords() })
            addRow(group, row("恢复弹幕默认设置", actionColor = accent) { activity.phoneResetDanmakuPrefs() })
        }
    }

    private fun phoneAppearanceLabel() = PortraitStyle.appearance.label

    private fun showAppearancePicker() {
        val choices = PortraitStyle.Appearance.entries
        PhoneDialog.choose(activity, "手机外观", choices.map { it.label },
            onChoice = { index -> choices.getOrNull(index)?.let { activity.phoneSetAppearance(it) } },
            focusIndex = choices.indexOf(PortraitStyle.appearance)).show()
    }

    private fun lookSection(parent: LinearLayout) {
        section(parent, "外观与界面", "手机外观可跟随系统或固定为浅色、深色，横屏和电视使用独立主题。") { group ->
            addRow(group, row("手机外观", phoneAppearanceLabel(), { phoneAppearanceLabel() }) { showAppearancePicker() })
            addRow(group, row("横屏与电视风格", TvStyle.theme.label, { TvStyle.theme.label }) { activity.phoneThemeToggle() })
            addRow(group, row("界面缩放", UiScale.label(activity), { UiScale.label(activity) }) { activity.phoneUiScalePicker() })
            addRow(group, row("启动方向", OrientationMode.of(library.orientationMode).label, { OrientationMode.of(library.orientationMode).label }) { activity.phoneOrientationPicker() })
        }
    }

    private fun deviceSection(parent: LinearLayout) {
        section(parent, "设备与数据") { group ->
            addRow(group, row("手机快传", "APK 与视频") { activity.phoneQuickTransfer() })
            addRow(group, row("恢复隐藏推荐", "${library.hidden().size} 部", { "${library.hidden().size} 部" }) { activity.phoneUnhideAll() })
            addRow(group, row("观看统计", "本机累计") { activity.phoneWatchStats() })
            addRow(group, row("清理缓存") { activity.phoneClearCache() })
        }
    }

    private fun aboutSection(parent: LinearLayout) {
        section(parent, "关于红果") { group ->
            addRow(group, row("版本与更新", BuildConfig.VERSION_NAME) { activity.phoneUpdater() })
            addRow(group, row("接口自检") { activity.phoneInterfaceCheck() })
            addRow(group, row("运行日志") { activity.phoneRuntimeLogs() })
            addRow(group, row("异常日志", "${CrashReporter.count(activity)} 份", { "${CrashReporter.count(activity)} 份" }) { activity.phoneCrashLogs() })
        }
        parent.addView(text("红果 ${BuildConfig.VERSION_NAME}", 12f, secondaryColor).apply { gravity = Gravity.CENTER }, lp())
        parent.addView(text("收藏与本机记录保存在设备，登录后可同步到红果账号。", 12f, secondaryColor).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }, lp())
        parent.addView(text("仅用于技术交流与学习，内容版权归原平台所有。", 12f, secondaryColor).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(12))
        }, lp())
    }
}
