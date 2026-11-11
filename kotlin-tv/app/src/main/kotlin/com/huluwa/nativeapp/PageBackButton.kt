// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import kotlin.math.roundToInt

/** 二级页顶栏共用的返回入口：矢量箭头、胶囊轮廓与遥控焦点反馈。 */
object PageBackButton {
    fun addTo(parent: LinearLayout, label: String = "返回", onClick: () -> Unit): TextView {
        val context = parent.context
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        val pressed = intArrayOf(android.R.attr.state_pressed)
        val focused = intArrayOf(android.R.attr.state_focused)
        val hovered = intArrayOf(android.R.attr.state_hovered)
        val idle = intArrayOf()
        val activeText = if (TvStyle.theme == TvStyle.Theme.NIGHT) TvStyle.background else Color.WHITE
        val states = arrayOf(pressed, focused, hovered, idle)
        val textColors = ColorStateList(states, intArrayOf(activeText, activeText, TvStyle.text, TvStyle.text))
        val iconColors = ColorStateList(states, intArrayOf(activeText, activeText, TvStyle.accent, TvStyle.accent))

        fun capsule(fill: Int, border: Int, stroke: Int = 1) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(24).toFloat()
            setStroke(dp(stroke).coerceAtLeast(1), border)
        }
        val surfaces = StateListDrawable().apply {
            addState(pressed, capsule(TvStyle.accent, TvStyle.accent, 2))
            addState(focused, capsule(TvStyle.accent, TvStyle.accent, 2))
            addState(hovered, capsule(TvStyle.raised, TvStyle.accent))
            addState(idle, capsule(TvStyle.surface, TvStyle.outline))
        }
        val arrow = DrawableCompat.wrap(requireNotNull(context.getDrawable(R.drawable.ic_page_back)).mutate()).apply {
            setBounds(0, 0, dp(20), dp(20))
            DrawableCompat.setTintList(this, iconColors)
        }
        val button = TextView(context).apply {
            id = View.generateViewId()
            text = label
            contentDescription = label
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            minWidth = dp(96)
            minHeight = dp(48)
            setPaddingRelative(dp(16), 0, dp(16), 0)
            setTextColor(textColors)
            setCompoundDrawablesRelative(arrow, null, null, null)
            compoundDrawablePadding = dp(8)
            background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(TvStyle.accent, 48)),
                surfaces,
                capsule(Color.WHITE, Color.TRANSPARENT),
            )
            isFocusable = true
            isFocusableInTouchMode = true
            TouchControls.focusOnTouch(this)
            setOnClickListener { onClick() }
        }
        parent.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            marginEnd = dp(12)
            gravity = Gravity.CENTER_VERTICAL
        })
        return button
    }
}
