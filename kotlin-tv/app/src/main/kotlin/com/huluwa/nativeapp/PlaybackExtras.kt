// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context

/**
 * 电视端本地播放增强设置：弹幕屏蔽词、遥控器按键与不兼容集跳过。
 * 只保存在本机，换机后重新设置即可。
 */
class PlaybackExtras(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 弹幕屏蔽词：命中任意一个即不展示该条弹幕。 */
    var blockedWords: Set<String>
        get() = prefs.getStringSet(KEY_BLOCKED, DEFAULT_BLOCKED)?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        set(value) { prefs.edit().putStringSet(KEY_BLOCKED, value.map { it.trim() }.filter { it.isNotEmpty() }.toSet()).apply() }

    fun blocks(text: String) = blockedWords.any { it.isNotEmpty() && text.contains(it, ignoreCase = true) }

    /** 平台只提供电视不可解码的清晰度时，自动跳到下一集（默认关闭，避免跳过用户想看的集数）。 */
    var autoSkipUnplayable: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SKIP, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_SKIP, value).apply() }

    /** 遥控器左右短按的快进步长（秒）。 */
    var seekStepSeconds: Int
        get() = prefs.getInt(KEY_SEEK_STEP, 10).takeIf { it in SEEK_STEPS } ?: 10
        set(value) { prefs.edit().putInt(KEY_SEEK_STEP, value).apply() }

    /** 长按右键快进时使用的倍速。 */
    var longPressSpeed: Float
        get() = prefs.getFloat(KEY_FAST_SPEED, 3f).takeIf { it in FAST_SPEEDS } ?: 3f
        set(value) { prefs.edit().putFloat(KEY_FAST_SPEED, value).apply() }

    /** 手机遥控模式沿用的菜单键行为；TV 播放页菜单键固定打开操作菜单。 */
    var menuKeyAction: String
        get() = prefs.getString(KEY_MENU, MENU_PANEL)?.takeIf { it in listOf(MENU_PANEL, MENU_EPISODES, MENU_DANMAKU) } ?: MENU_PANEL
        set(value) { prefs.edit().putString(KEY_MENU, value).apply() }

    /** 遥控上下键偏好；TV 仅影响上键（操作菜单 / 上一集），下键固定打开操作菜单。 */
    var dpadUpDownAction: String
        get() = prefs.getString(KEY_UPDOWN, UPDOWN_PANEL)?.takeIf { it in listOf(UPDOWN_PANEL, UPDOWN_SWITCH) } ?: UPDOWN_PANEL
        set(value) { prefs.edit().putString(KEY_UPDOWN, value).apply() }

    companion object {
        private const val PREFS = "playback-extras-v1"
        private const val KEY_BLOCKED = "blockedWords"
        private const val KEY_AUTO_SKIP = "autoSkipUnplayable"
        private const val KEY_SEEK_STEP = "seekStepSeconds"
        private const val KEY_FAST_SPEED = "longPressSpeed"
        private const val KEY_MENU = "menuKeyAction"
        private const val KEY_UPDOWN = "dpadUpDownAction"
        val SEEK_STEPS = listOf(10, 15, 30, 60)
        val FAST_SPEEDS = listOf(2f, 3f, 4f)
        const val MENU_PANEL = "panel"
        const val MENU_EPISODES = "episodes"
        const val MENU_DANMAKU = "danmaku"
        const val UPDOWN_PANEL = "panel"
        const val UPDOWN_SWITCH = "switch"
        private val DEFAULT_BLOCKED = setOf("加群", "微信", "公众号", "QQ群")
    }
}
