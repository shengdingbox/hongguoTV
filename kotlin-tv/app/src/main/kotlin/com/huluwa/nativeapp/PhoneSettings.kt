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
import com.hongguotv.core.PlaybackSpeed

/** 手机设置页沿用竖屏配色、分组标题与圆角卡片，设置操作复用原有桥接。 */
class PhoneSettings(private val activity: MainActivity) {
    private val library get() = activity.phoneLibrary
    private val pageColor get() = PortraitStyle.background
    private val groupColor get() = PortraitStyle.surface
    private val secondaryColor get() = PortraitStyle.secondaryText
    private val separatorColor get() = PortraitStyle.outline
    private val accent get() = PortraitStyle.navigationSelected
    private val pagePadding get() = dp(16)

    private class Binding(val provider: () -> String?, val view: TextView)
    private class ToggleBinding(val provider: () -> Boolean, val view: PhoneSettingsSwitch)
    private val bindings = mutableListOf<Binding>()
    private val toggles = mutableListOf<ToggleBinding>()
    private var refreshingValues = false
    private var lastScrollY = 0
    private var activeScroll: NestedScrollView? = null

    private fun dp(value: Int) = PhoneUi.dp(activity, value)
    private fun text(value: String, size: Float = 16f, color: Int = PortraitStyle.text) = PhoneUi.text(activity, value, size, color)
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
            accessibilityHeadingCompat()
        }
        bar.addView(inlineTitle, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        navigation.addView(bar, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))
        val hairline = View(activity).apply { setBackgroundColor(separatorColor) }
        navigation.addView(hairline, FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM))
        shell.addView(navigation, lp(-1, dp(48)))

        val scroll = NestedScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            clipToPadding = false
        }
        activeScroll = scroll
        val content = PhoneUi.column(activity).apply { setPadding(pagePadding, dp(16), pagePadding, dp(24)) }
        accountCard(content)
        signingSection(content)
        playbackSection(content)
        offlineSection(content)
        lookSection(content)
        deviceSection(content)
        aboutSection(content)
        scroll.addView(content, lp())
        shell.addView(scroll, lp(-1, 0).apply { weight = 1f })
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))

        PhoneInsets.listen(shell) { topInset, bottomInset, _ ->
            navigation.layoutParams = lp(-1, dp(48) + topInset)
            navigation.setPadding(0, topInset, 0, 0)
            content.setPadding(pagePadding, dp(16), pagePadding, dp(24) + bottomInset)
        }
        scroll.viewTreeObserver.addOnScrollChangedListener {
            if (activeScroll === scroll) {
                lastScrollY = scroll.scrollY
            }
        }
        scroll.post {
            if (activeScroll === scroll) {
                scroll.scrollTo(0, restoreY)
                lastScrollY = scroll.scrollY
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
        background = PortraitStyle.shape(activity, groupColor, radius = 16)
        clipToOutline = true
    }

    private fun section(parent: LinearLayout, title: String, build: (LinearLayout) -> Unit) {
        val section = PhoneUi.column(activity)
        section.addView(text(title, 20f).apply {
            setTypeface(null, Typeface.BOLD)
            accessibilityHeadingCompat()
        }, lp().apply { bottomMargin = dp(12) })
        val group = card()
        build(group)
        section.addView(group, lp())
        parent.addView(section, lp().apply { bottomMargin = dp(24) })
    }

    private fun addRow(parent: LinearLayout, line: LinearLayout) {
        if (parent.childCount > 0) parent.addView(View(activity).apply {
            setBackgroundColor(separatorColor)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, lp(-1, 1).apply { leftMargin = dp(16); rightMargin = dp(16) })
        parent.addView(line, lp())
    }

    private fun baseRow(onClick: (() -> Unit)?): LinearLayout = PhoneUi.row(activity).apply {
        isBaselineAligned = false
        minimumHeight = dp(56)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 0)
        isClickable = onClick != null
        isFocusable = onClick != null
        if (onClick != null) setOnClickListener { onClick() }
        setOnFocusChangeListener { _, focused -> if (focused) refreshValues() }
    }

    private fun row(label: String, value: String? = null, provider: (() -> String?)? = null,
        valueColor: Int = secondaryColor, actionColor: Int? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val line = baseRow(onClick)
        line.addView(text(label, color = actionColor ?: PortraitStyle.text).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, lp(0, -2).apply { weight = 1f })
        if (value != null) {
            val current = text(value, 15f, valueColor).apply {
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
            setPadding(dp(16), dp(6), dp(14), dp(6))
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
            minimumHeight = dp(92)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        line.addView(PhoneSettingsAvatar(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, lp(dp(56), dp(56)).apply { rightMargin = dp(12) })
        val labels = PhoneUi.column(activity)
        labels.addView(text(if (account.signedIn) account.displayName else "红果账号", 18f).apply {
            setTypeface(null, Typeface.BOLD)
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

    private fun signingSection(parent: LinearLayout) {
        section(parent, "算法模式") { group ->
            addRow(group, row("计算方式", activity.phoneSigningModeLabel, { activity.phoneSigningModeLabel }, valueColor = accent) {
                activity.phoneSigningModePicker()
            })
        }
    }

    private fun offlineSection(parent: LinearLayout) {
        section(parent, "离线缓存") { group ->
            val value = { "${activity.offlineDownloads.concurrency} 集" }
            addRow(group, row("同时下载", value(), value) {
                PhoneOfflineLibrary.configureConcurrency(activity, ::refreshValues)
            })
        }
    }

    private fun playbackSection(parent: LinearLayout) {
        section(parent, "播放与观看") { group ->
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

    private fun phoneAppearanceLabel() = PortraitStyle.appearance.label

    private fun showAppearancePicker() {
        val choices = PortraitStyle.Appearance.entries
        PhoneDialog.choose(activity, "手机外观", choices.map { it.label },
            onChoice = { index -> choices.getOrNull(index)?.let { activity.phoneSetAppearance(it) } },
            focusIndex = choices.indexOf(PortraitStyle.appearance)).show()
    }

    private fun lookSection(parent: LinearLayout) {
        section(parent, "外观与界面") { group ->
            addRow(group, row("手机外观", phoneAppearanceLabel(), { phoneAppearanceLabel() }) { showAppearancePicker() })
            addRow(group, row("横屏与电视风格", TvStyle.theme.label, { TvStyle.theme.label }) { activity.phoneThemeToggle() })
            addRow(group, row("界面缩放", UiScale.label(activity), { UiScale.label(activity) }) { activity.phoneUiScalePicker() })
            addRow(group, row("启动方向", OrientationMode.of(library.orientationMode).label, { OrientationMode.of(library.orientationMode).label }) { activity.phoneOrientationPicker() })
        }
    }

    private fun deviceSection(parent: LinearLayout) {
        section(parent, "设备与数据") { group ->
            addRow(group, row("恢复隐藏推荐", "${library.hidden().size} 部", { "${library.hidden().size} 部" }) { activity.phoneUnhideAll() })
            addRow(group, row("观看统计", "本机累计") { activity.phoneWatchStats() })
            addRow(group, row("清理缓存") { activity.phoneClearCache() })
        }
    }

    private fun aboutSection(parent: LinearLayout) {
        section(parent, "关于红果") { group ->
            addRow(group, row("接口自检") { activity.phoneInterfaceCheck() })
            addRow(group, row("运行日志") { activity.phoneRuntimeLogs() })
            addRow(group, row("异常日志", "${CrashReporter.count(activity)} 份", { "${CrashReporter.count(activity)} 份" }) { activity.phoneCrashLogs() })
        }
        parent.addView(text("红果 ${BuildConfig.VERSION_NAME}", 12f, PortraitStyle.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }, lp())
    }
}
