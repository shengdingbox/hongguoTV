// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

/**
 * 启动方向偏好：决定手机上走竖屏页面还是 TV 横屏页面，与设备当前朝向解耦。
 * 横屏只影响「用哪套界面皮肤」，设置页本身在竖屏下也是完整的，不需要转横屏才能调。
 * 电视/盒子一律锁横屏，不受这个偏好影响（见 PhoneUi.requestedOrientation）。
 */
enum class OrientationMode(val label: String) {
    /** 跟随系统：竖着拿走竖屏页面（含完整设置页），横过来走 TV 页面（原有行为）。 */
    AUTO("跟随系统"),
    /** 强制横屏：始终按横屏布局显示，竖着拿也保持横向界面（设置页在横屏下用电视版）。 */
    LANDSCAPE("强制横屏"),
    /** 强制竖屏：始终按竖屏布局显示，设置集中在手机竖屏设置页。 */
    PORTRAIT("强制竖屏");

    companion object { fun of(raw: String?) = entries.firstOrNull { it.name == raw } ?: AUTO }
}
