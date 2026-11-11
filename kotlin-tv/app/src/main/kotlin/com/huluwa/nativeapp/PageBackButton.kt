// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.drawable.DrawableCompat
import kotlin.math.roundToInt

/** 二级页顶栏共用的返回入口：白色尖角图标与文字，常态无底色、无边框。 */
object PageBackButton {
    fun addTo(parent: LinearLayout, label: String = "返回", onClick: () -> Unit): TextView {
        val context = parent.context
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        val arrow = DrawableCompat.wrap(requireNotNull(context.getDrawable(R.drawable.ic_page_back)).mutate()).apply {
            setBounds(0, 0, dp(12), dp(20))
            DrawableCompat.setTint(this, Color.WHITE)
        }
        val button = TextView(context).apply {
            id = View.generateViewId()
            text = label
            contentDescription = label
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            includeFontPadding = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            minWidth = dp(64)
            minHeight = dp(48)
            setPaddingRelative(dp(4), 0, dp(4), 0)
            setTextColor(Color.WHITE)
            setCompoundDrawablesRelative(arrow, null, null, null)
            compoundDrawablePadding = dp(4)
            background = RippleDrawable(
                ColorStateList.valueOf(0x26FFFFFF),
                null,
                GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(6).toFloat()
                },
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
