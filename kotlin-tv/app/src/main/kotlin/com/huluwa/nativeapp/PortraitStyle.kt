// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/** 竖屏页面独立配色，横屏和电视继续使用 TvStyle。 */
object PortraitStyle {
    val background = Color.WHITE
    val surface = Color.rgb(247, 247, 247)
    val raised = Color.rgb(239, 239, 239)
    val accent = Color.rgb(78, 141, 43)
    val text = Color.rgb(17, 17, 17)
    val muted = Color.rgb(145, 145, 145)
    val outline = Color.rgb(228, 228, 228)
    val heroTop = Color.rgb(187, 167, 159)
    val heroMiddle = Color.rgb(232, 226, 220)
    const val POSTER_ASPECT = .75f

    fun shape(context: Context, color: Int, radius: Int = 10, border: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * context.resources.displayMetrics.density
            if (border != Color.TRANSPARENT) setStroke((context.resources.displayMetrics.density).toInt().coerceAtLeast(1), border)
        }

    fun navigationItem(context: Context, selected: Boolean): RippleDrawable {
        val density = context.resources.displayMetrics.density
        val glass = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (selected) intArrayOf(
            Color.argb(160, 255, 255, 255), Color.argb(70, 255, 255, 255), Color.argb(100, 215, 222, 208),
        ) else intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT)).apply {
            cornerRadius = 28f * density
            if (selected) setStroke(density.toInt().coerceAtLeast(1), Color.argb(170, 255, 255, 255))
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(35, 90, 110, 80)), glass, shape(context, Color.WHITE, 28))
    }

    fun selectable(context: Context, color: Int, radius: Int = 10) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(28, 0, 0, 0)),
        shape(context, color, radius),
        shape(context, Color.WHITE, radius),
    )
}
