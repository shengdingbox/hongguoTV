// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * 设备形态识别与尺寸换算。
 *
 * 电视界面按远距离观看设计，字号、间距和控件高度都偏大。手机屏幕更小、观看距离更近，
 * 直接沿用会显得臃肿，因此在手机形态下统一下调尺寸系数，避免每处硬编码重复判断。
 * 仅在主线程的 Activity 创建和配置变化时写入。
 */
object UiProfile {
    /** 是否为手机等小屏触控设备。 */
    var compact=false
        private set
    /** dp、间距与控件高度的缩放系数；电视为 1。 */
    var scale=1f
        private set
    /** 字号（sp）缩放系数。 */
    var textScale=1f
        private set

    fun configure(context: Context) {
        val config=context.resources.configuration
        val mode=config.uiMode and Configuration.UI_MODE_TYPE_MASK
        val television=mode==Configuration.UI_MODE_TYPE_TELEVISION
        val touch=context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        val smallest=config.smallestScreenWidthDp
        // 电视和电视盒子始终按电视处理；触屏且窄于 720dp 的设备视为手机。
        compact=!television && touch && smallest in 1..719
        scale=if(compact) .85f else 1f
        textScale=if(compact) .88f else 1f
    }

    fun dp(context: Context,value: Number)=
        (value.toFloat()*context.resources.displayMetrics.density*scale).toInt()

    fun sp(value: Float)=value*textScale

    /** 页面可见宽度占屏幕的比例：手机把电视的 10% 留白收窄一些。 */
    val contentFraction=if(compact) .92f else .9f

    /** 触屏上可点击控件的最小高度（dp）；电视仍使用原来的量值。 */
    fun touchHeight(context: Context)=if(compact) (44*context.resources.displayMetrics.density).toInt() else 0

    /** 海报列数：电视固定六列，手机按可用宽度减列，避免窄屏挤成细条。 */
    fun columns(widthDp: Float)=if(compact) (widthDp/150f).toInt().coerceIn(3,6) else 6
}
