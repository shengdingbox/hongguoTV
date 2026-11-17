// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** 手机方向策略与公共视图工具；竖屏内容统一由 PortraitUi 渲染，电视与横屏使用 TV 页面。 */
object PhoneUi {

    /** 当前是否应使用手机竖屏页面：触屏手持设备，且界面方向为竖（实际朝向或用户强制竖屏）。
     *  [OrientationMode] 里强制横屏会让这里恒为 false，强制竖屏则恒为 true。 */
    fun shouldUse(activity: Activity): Boolean {
        if(!TouchControls.isHandheld(activity)) return false
        return when(orientationMode(activity)) {
            OrientationMode.PORTRAIT -> true
            OrientationMode.LANDSCAPE -> false
            // 跟随系统：横屏仍走 TV 页面，只有竖着拿才接管
            OrientationMode.AUTO -> activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        }
    }

    /**
     * 播放页用哪套皮肤：
     *  - 强制横屏 / 跟随系统且横着拿 → 电视播放器（遥控向界面，手机不接管）；
     *  - 强制竖屏 → 手机播放页（竖屏那套；点全屏再转成横屏全屏皮肤）；
     *  - 跟随系统且竖着拿 → 手机播放页。
     */
    fun shouldUsePlayer(activity: Activity): Boolean = shouldUse(activity)

    /**
     * 手机播放页的横屏全屏皮肤（[PhonePlayer] 的 fullscreen 布局）。
     *
     * 与电视播放器严格区分：手机强制竖屏 / 跟随系统下点「全屏」都旋转并切成这套
     * 第三方参考实现风格的横屏全屏界面；只有强制横屏（[OrientationMode.LANDSCAPE]）和
     * 真正的电视设备才走 TV 播放器那套。
     */
    fun shouldUsePhoneFullscreen(activity: Activity): Boolean =
        TouchControls.isHandheld(activity) && orientationMode(activity) != OrientationMode.LANDSCAPE

    /** 用户设置里的启动方向偏好（每次实时读，设置改完立即生效）。 */
    fun orientationMode(activity: Activity): OrientationMode =
        OrientationMode.of(StartupSettings.string("orientationMode"))

    /** Activity 请求的屏幕方向：电视锁横屏；手机按偏好，强制档位锁死朝向，跟随系统则交给传感器。 */
    fun requestedOrientation(activity: Activity): Int {
        if(!TouchControls.isHandheld(activity)) return android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        return when(orientationMode(activity)) {
            OrientationMode.LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationMode.PORTRAIT -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationMode.AUTO -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // ---- 设置页、播放页与账号组件共用的视图工具 ----

    fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun column(activity: Activity) = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    fun row(activity: Activity) = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(w, h)

    fun text(activity: Activity, value: String, size: Float, color: Int = TvStyle.text): TextView =
        TextView(activity).apply { text = value; textSize = size; setTextColor(color); includeFontPadding = false }

    fun toast(activity: Activity, message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
