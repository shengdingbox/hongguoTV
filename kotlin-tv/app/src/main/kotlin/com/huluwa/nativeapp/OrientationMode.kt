// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

/**
 * 启动方向偏好：决定手机上走竖屏页面还是 TV 横屏页面，与设备当前朝向解耦。
 * 电视/盒子一律锁横屏，不受这个偏好影响（见 MainActivity.preferredOrientation）。
 */
enum class OrientationMode(val label: String) {
    /** 跟随系统：竖着拿走竖屏页面，横过来走 TV 页面（原有行为）。 */
    AUTO("跟随系统"),
    /** 强制横屏：始终按横屏布局显示，竖着拿也保持横向界面。 */
    LANDSCAPE("强制横屏"),
    /** 强制竖屏：始终按竖屏布局显示。 */
    PORTRAIT("强制竖屏");

    companion object { fun of(raw: String?) = entries.firstOrNull { it.name == raw } ?: AUTO }
}
