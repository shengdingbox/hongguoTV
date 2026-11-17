// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.ContextThemeWrapper

/** 竖屏内容独立选择外观；不改变电视和播放器使用的 TvStyle。 */
object PortraitStyle {
    enum class Appearance(val label: String) {
        SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色");

        companion object {
            fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: SYSTEM
        }
    }

    private const val APPEARANCE_KEY = "phoneAppearance"
    var appearance = Appearance.SYSTEM
        private set
    var isDark = false
        private set
    /** 用于淘汰使用旧配色构造的页面缓存。 */
    var revision = 0L
        private set

    /** 在启动、系统外观变化和恢复前台时调用；返回是否需要重建内容页。 */
    fun refresh(context: Context): Boolean {
        val stored = StartupSettings.string(APPEARANCE_KEY)
        appearance = Appearance.fromStored(stored)
        return refresh(context.resources.configuration)
    }

    /** 手机外观只保存在本机，旧版本未设置时继续跟随系统。 */
    fun setAppearance(context: Context, value: Appearance): Boolean {
        StartupSettings.set(APPEARANCE_KEY, value.name)
        appearance = value
        return refresh(context.resources.configuration)
    }

    fun refresh(configuration: Configuration): Boolean {
        val next = when (appearance) {
            Appearance.SYSTEM -> configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            Appearance.LIGHT -> false
            Appearance.DARK -> true
        }
        if (next == isDark) return false
        isDark = next
        revision++
        return true
    }

    val background get() = if (isDark) Color.rgb(18, 18, 18) else Color.WHITE
    val surface get() = if (isDark) Color.rgb(27, 27, 29) else Color.rgb(247, 247, 247)
    val raised get() = if (isDark) Color.rgb(43, 43, 46) else Color.rgb(239, 239, 239)
    val accent get() = if (isDark) Color.rgb(143, 191, 105) else Color.rgb(78, 141, 43)
    val onAccent get() = if (isDark) Color.rgb(18, 30, 12) else Color.WHITE
    // 第三方参考 iOS 客户端的 AccentColor；深色下提高亮度，保持图标及文字清晰。
    val navigationSelected get() = if (isDark) Color.rgb(151, 201, 110) else Color.rgb(74, 140, 42)
    val text get() = if (isDark) Color.rgb(241, 241, 243) else Color.rgb(17, 17, 17)
    val muted get() = if (isDark) Color.rgb(165, 165, 173) else Color.rgb(145, 145, 145)
    val secondaryText get() = if (isDark) Color.rgb(207, 207, 212) else Color.rgb(60, 60, 60)
    val outline get() = if (isDark) Color.rgb(61, 61, 65) else Color.rgb(228, 228, 228)
    val heroTop get() = if (isDark) Color.rgb(68, 56, 53) else Color.rgb(187, 167, 159)
    val heroMiddle get() = if (isDark) Color.rgb(37, 32, 31) else Color.rgb(232, 226, 220)
    val selectedSurface get() = if (isDark) Color.rgb(75, 75, 80) else Color.WHITE
    val chipSelected get() = if (isDark) Color.rgb(231, 231, 235) else Color.BLACK
    val onChipSelected get() = if (isDark) Color.rgb(20, 20, 22) else Color.WHITE
    val subtleButton get() = if (isDark) Color.rgb(59, 59, 63) else Color.rgb(215, 215, 215)
    val translucentSurface get() = if (isDark) Color.argb(185, 34, 34, 37) else Color.argb(145, 255, 255, 255)
    val ripple get() = if (isDark) Color.argb(35, 255, 255, 255) else Color.argb(28, 0, 0, 0)
    const val POSTER_ASPECT = .75f

    /** 原生菜单使用与竖屏内容一致的外观，不沿用固定的电视 Activity 主题。 */
    fun popupContext(context: Context): Context = ContextThemeWrapper(context,
        if (isDark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light)

    fun shape(context: Context, color: Int, radius: Int = 10, border: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * context.resources.displayMetrics.density
            if (border != Color.TRANSPARENT) setStroke((context.resources.displayMetrics.density).toInt().coerceAtLeast(1), border)
        }

    /** 选中胶囊统一由导航底板绘制，菜单项仅保留中性的触摸反馈。 */
    fun navigationItem(context: Context) = RippleDrawable(
        ColorStateList.valueOf(ripple),
        null,
        shape(context, Color.WHITE, 28),
    )

    fun selectable(context: Context, color: Int, radius: Int = 10) = RippleDrawable(
        ColorStateList.valueOf(ripple),
        shape(context, color, radius),
        shape(context, Color.WHITE, radius),
    )
}
