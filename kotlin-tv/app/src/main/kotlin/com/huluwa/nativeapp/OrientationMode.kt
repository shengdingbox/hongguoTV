// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

/**
 * 启动方向偏好：手机默认跟随系统，竖屏统一使用 iOS 风格页面，横屏使用 TV 页面。
 * 横屏只影响「用哪套界面皮肤」，设置页本身在竖屏下也是完整的，不需要转横屏才能调。
 * 例外：播放页的全屏按钮在强制竖屏下也会转横屏，但用的是手机横屏全屏皮肤
 * （见 [PhoneUi.shouldUsePhoneFullscreen]），不是电视播放器。
 * 电视/盒子一律锁横屏，不受这个偏好影响（见 PhoneUi.requestedOrientation）。
 */
enum class OrientationMode(val label: String) {
    /** 跟随系统（默认）：竖着拿走新版竖屏页面（含完整设置页），横过来走 TV 页面。 */
    AUTO("跟随系统"),
    /** 强制横屏：始终按横屏布局显示，竖着拿也保持横向界面（设置页在横屏下用电视版）。 */
    LANDSCAPE("强制横屏"),
    /** 强制竖屏：始终按竖屏布局显示，设置集中在手机竖屏设置页；
     *  播放页点全屏会转成横屏，走手机那套横屏全屏界面（仍不是电视播放器）。 */
    PORTRAIT("强制竖屏");

    companion object {
        /** 未设置方向时默认跟随系统；电视/盒子的横屏策略由设备识别单独决定。 */
        fun of(raw: String?) = entries.firstOrNull { it.name == raw } ?: AUTO
    }
}
